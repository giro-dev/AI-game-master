package dev.agiro.masterserver.pdf_extractor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.ai.retry.NonTransientAiException;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PdfProcessingService {

    private final VectorStore vectorStore;
    private final PDFDocumentReader pdfDocumentReader;

    private static final int REQUESTS_LIMIT_PER_MINUTE = 90;     // RPM (keep some headroom)
    private static final int TOKENS_LIMIT_PER_MINUTE = 38_000; // TPM (keep some headroom)
    private static final Duration WINDOW_DURATION = Duration.ofMinutes(1);
    private static final int MAX_RETRIES_ON_429 = 5;

    // Rough token estimate (~4 chars per token for English)
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / 4.0);
    }

    public int processPdfByTableOfContents(MultipartFile file, String foundrySystem) throws java.io.IOException {
        List<Document> docsFromPdfWithCatalog = pdfDocumentReader.getDocsFromPdfWithCatalog(file, foundrySystem);

        TokenTextSplitter textSplitter = TokenTextSplitter.builder()
                .withChunkSize(2000)
                .withMinChunkSizeChars(300)
                .build();

        var metadataEnricher = new GameMasterMetadataEnricher(file.getOriginalFilename(), foundrySystem);

        List<Document> documents = docsFromPdfWithCatalog.stream()
                .map(textSplitter::split)
                .map(metadataEnricher)
                .flatMap(Collection::stream)
                .toList();

        addWithRateLimit(documents);
        return documents.size();
    }

    // Throttle by RPM and TPM; retry on 429 with exponential backoff.
    private void addWithRateLimit(List<Document> documents) {
        long windowStartMs = System.currentTimeMillis();
        int requestsInWindow = 0;
        int tokensInWindow = 0;

        for (Document doc : documents) {
            int tokens = estimateTokens(doc.getText());

            // If adding this doc would exceed the current window limits, sleep until the window resets.
            long now = System.currentTimeMillis();
            long elapsed = now - windowStartMs;
            if (requestsInWindow >= REQUESTS_LIMIT_PER_MINUTE ||
                    tokensInWindow + tokens > TOKENS_LIMIT_PER_MINUTE) {

                long sleepMs = WINDOW_DURATION.toMillis() - elapsed;
                if (sleepMs > 0) {
                    log.debug("Throttling: sleeping {} ms to respect rate limits (requests={}, tokens={})",
                            sleepMs, requestsInWindow, tokensInWindow);
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                windowStartMs = System.currentTimeMillis();
                requestsInWindow = 0;
                tokensInWindow = 0;
            }

            // Try sending this document; retry on 429 with backoff.
            sendWithRetry(doc);

            requestsInWindow += 1;      // 1 request per document
            tokensInWindow += tokens;    // approximate token usage for TPM
        }
    }

    private void sendWithRetry(Document doc) {
        int attempt = 0;
        while (true) {
            try {
                vectorStore.add(List.of(doc)); // 1 request per doc for predictable throttling
                return;
            } catch (NonTransientAiException ex) {
                // HTTP 429 or quota messages -> backoff and retry
                String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
                boolean is429OrQuota = msg.contains("429") || msg.contains("rate limit") || msg.contains("insufficient_quota");
                if (!is429OrQuota || attempt >= MAX_RETRIES_ON_429) {
                    throw ex;
                }
                attempt++;
                long backoffMs = (long) Math.min(60_000, Math.pow(2, attempt) * 1_000L); // capped exponential backoff
                log.warn("Embedding request throttled (attempt {}), backing off {} ms: {}", attempt, backoffMs, ex.getMessage());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}