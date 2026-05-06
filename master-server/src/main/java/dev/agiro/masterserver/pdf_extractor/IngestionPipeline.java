package dev.agiro.masterserver.pdf_extractor;

import dev.agiro.masterserver.controller.WebSocketController;
import dev.agiro.masterserver.dto.BookDto;
import dev.agiro.masterserver.dto.CompendiumIngestionRequest;
import dev.agiro.masterserver.dto.IngestionEvent;
import dev.agiro.masterserver.dto.WebSocketMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.ai.document.Document;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Multi-stage ingestion pipeline for RPG documents.
 * <ol>
 *   <li>Parse &amp; split (PDF → chunks)</li>
 *   <li>Table detection &amp; markdown conversion</li>
 *   <li>AI classification (rules, character_creation, item_definition, …)</li>
 *   <li>Entity extraction (structured JSON from entity-bearing chunks)</li>
 *   <li>Enrich metadata &amp; store in OpenSearch</li>
 * </ol>
 * Runs asynchronously and reports progress via WebSocket.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionPipeline {

    private final PDFDocumentReader pdfDocumentReader;
    private final DocumentClassifier documentClassifier;
    private final EntityExtractor entityExtractor;
    private final VectorStore vectorStore;
    private final WebSocketController webSocketController;
    private final dev.agiro.masterserver.service.SystemProfileService systemProfileService;
    private final OpenSearchClient openSearchClient;

    /** In-memory book registry (replace with a persistent store if needed) */
    private final Map<String, BookDto> bookRegistry = new ConcurrentHashMap<>();

    // Rate-limit settings for embedding API
    private static final int RPM_LIMIT = 90;
    private static final int TPM_LIMIT = 38_000;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_RETRIES = 5;

    // ─── public API ───────────────────────────────────────────────────────

    /**
     * Start asynchronous ingestion of a PDF.
     *
     * @return bookId (UUID)
     */
    @Async
    public void ingestAsync(byte[] pdfBytes, String fileName, String worldId, String foundrySystem,
                            String bookTitle, String sessionId) {

        BookDto book = createBookRecord(worldId, foundrySystem, bookTitle, fileName, "pdf", fileName);
        String bookId = book.getBookId();

        sendProgress(sessionId, IngestionEvent.builder()
                .bookId(bookId).worldId(worldId).status("STARTED").progress(0)
                .message("Starting ingestion of " + bookTitle)
                .build(), WebSocketMessage.MessageType.INGESTION_STARTED);

        try {
            // ── Stage 1: Parse & Split ──────────────────────────────────
            book.setStatus("PARSING");
            sendProgress(sessionId, IngestionEvent.builder()
                    .bookId(bookId).worldId(worldId).status("PARSING").progress(10)
                    .message("Parsing PDF and splitting into chunks…")
                    .build(), WebSocketMessage.MessageType.INGESTION_PROGRESS);

            List<Document> rawDocs = pdfDocumentReader.getDocsFromPdfWithCatalog(pdfBytes, foundrySystem);

            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(2000)
                    .withMinChunkSizeChars(300)
                    .withMinChunkLengthToEmbed(50)
                    .build();

            // Table detection
            TableToMarkdownDocumentTransformer tableTransformer = new TableToMarkdownDocumentTransformer();

            List<Document> chunks = rawDocs.stream()
                    .map(splitter::split)
                    .flatMap(Collection::stream)
                    .toList();

            chunks = tableTransformer.apply(new ArrayList<>(chunks));

            // Enrich base metadata
            enrichBaseMetadata(chunks, bookId, worldId, foundrySystem, bookTitle, fileName);

            int totalChunks = chunks.size();
            log.info("[{}] Stage 1 complete: {} chunks", bookId, totalChunks);

            sendProgress(sessionId, IngestionEvent.builder()
                    .bookId(bookId).worldId(worldId).status("PARSING").progress(25)
                    .message("Parsed " + totalChunks + " chunks")
                    .totalChunks(totalChunks)
                    .build(), WebSocketMessage.MessageType.INGESTION_PROGRESS);

            // ── Stage 2: AI Classification ──────────────────────────────
            book.setStatus("CLASSIFYING");
            sendProgress(sessionId, IngestionEvent.builder()
                    .bookId(bookId).worldId(worldId).status("CLASSIFYING").progress(30)
                    .message("Classifying chunks with AI…")
                    .totalChunks(totalChunks)
                    .build(), WebSocketMessage.MessageType.INGESTION_PROGRESS);

            List<Document> classifiedChunks = documentClassifier.classify(chunks);
            log.info("[{}] Stage 2 complete: classified {} chunks", bookId, classifiedChunks.size());

            sendProgress(sessionId, IngestionEvent.builder()
                    .bookId(bookId).worldId(worldId).status("CLASSIFYING").progress(55)
                    .message("Classification complete")
                    .totalChunks(totalChunks)
                    .build(), WebSocketMessage.MessageType.INGESTION_PROGRESS);

            // ── Stage 3: Entity Extraction ──────────────────────────────
            book.setStatus("EXTRACTING");
            sendProgress(sessionId, IngestionEvent.builder()
                    .bookId(bookId).worldId(worldId).status("EXTRACTING").progress(60)
                    .message("Extracting structured entities…")
                    .totalChunks(totalChunks)
                    .build(), WebSocketMessage.MessageType.INGESTION_PROGRESS);

            List<Document> entityDocs = entityExtractor.extractEntities(classifiedChunks);
            log.info("[{}] Stage 3 complete: {} entities extracted", bookId, entityDocs.size());

            sendProgress(sessionId, IngestionEvent.builder()
                    .bookId(bookId).worldId(worldId).status("EXTRACTING").progress(75)
                    .message("Extracted " + entityDocs.size() + " entities")
                    .totalChunks(totalChunks).entitiesExtracted(entityDocs.size())
                    .build(), WebSocketMessage.MessageType.INGESTION_PROGRESS);

            // ── Stage 4: Mark raw chunks & store ────────────────────────
            completeStructuredIngestion(book, sessionId, foundrySystem, classifiedChunks, entityDocs);

        } catch (Exception e) {
            log.error("[{}] Ingestion failed", book.getBookId(), e);
            book.setStatus("FAILED");
            sendProgress(sessionId, IngestionEvent.builder()
                    .bookId(book.getBookId()).worldId(worldId).status("FAILED").progress(100)
                    .message("Ingestion failed").error(e.getMessage())
                    .build(), WebSocketMessage.MessageType.INGESTION_FAILED);
        }
    }

    @Async
    public void ingestCompendiumAsync(CompendiumIngestionRequest request) {
        String worldId = request.getWorldId();
        String foundrySystem = request.getFoundrySystem();
        String packId = request.getPackId();
        String packLabel = request.getPackLabel() != null && !request.getPackLabel().isBlank()
                ? request.getPackLabel()
                : packId;

        BookDto book = createBookRecord(
                worldId,
                foundrySystem,
                "Compendium: " + packLabel,
                packId,
                "compendium",
                packId
        );

        sendProgress(request.getSessionId(), IngestionEvent.builder()
                .bookId(book.getBookId()).worldId(worldId).status("STARTED").progress(0)
                .message("Starting ingestion of compendium " + packLabel)
                .build(), WebSocketMessage.MessageType.INGESTION_STARTED);

        try {
            book.setStatus("PARSING");
            sendProgress(request.getSessionId(), IngestionEvent.builder()
                    .bookId(book.getBookId()).worldId(worldId).status("PARSING").progress(10)
                    .message("Collecting compendium entries…")
                    .build(), WebSocketMessage.MessageType.INGESTION_PROGRESS);

            List<Document> rawDocs = request.getEntries().stream()
                    .map(entry -> toCompendiumDocument(entry, request, book.getBookId()))
                    .collect(Collectors.toCollection(ArrayList::new));

            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(2000)
                    .withMinChunkSizeChars(300)
                    .withMinChunkLengthToEmbed(50)
                    .build();

            List<Document> chunks = rawDocs.stream()
                    .map(splitter::split)
                    .flatMap(Collection::stream)
                    .collect(Collectors.toCollection(ArrayList::new));

            enrichBaseMetadata(chunks, book.getBookId(), worldId, foundrySystem, book.getBookTitle(), packId);

            int totalChunks = chunks.size();
            sendProgress(request.getSessionId(), IngestionEvent.builder()
                    .bookId(book.getBookId()).worldId(worldId).status("PARSING").progress(25)
                    .message("Prepared " + request.getEntries().size() + " entries into " + totalChunks + " chunks")
                    .totalChunks(totalChunks)
                    .build(), WebSocketMessage.MessageType.INGESTION_PROGRESS);

            book.setStatus("CLASSIFYING");
            sendProgress(request.getSessionId(), IngestionEvent.builder()
                    .bookId(book.getBookId()).worldId(worldId).status("CLASSIFYING").progress(30)
                    .message("Classifying compendium content…")
                    .totalChunks(totalChunks)
                    .build(), WebSocketMessage.MessageType.INGESTION_PROGRESS);

            List<Document> classifiedChunks = documentClassifier.classify(chunks);

            book.setStatus("EXTRACTING");
            sendProgress(request.getSessionId(), IngestionEvent.builder()
                    .bookId(book.getBookId()).worldId(worldId).status("EXTRACTING").progress(60)
                    .message("Extracting entities from compendium…")
                    .totalChunks(totalChunks)
                    .build(), WebSocketMessage.MessageType.INGESTION_PROGRESS);

            List<Document> entityDocs = entityExtractor.extractEntities(classifiedChunks);

            completeStructuredIngestion(book, request.getSessionId(), foundrySystem, classifiedChunks, entityDocs);
        } catch (Exception e) {
            log.error("[{}] Compendium ingestion failed", book.getBookId(), e);
            book.setStatus("FAILED");
            sendProgress(request.getSessionId(), IngestionEvent.builder()
                    .bookId(book.getBookId()).worldId(worldId).status("FAILED").progress(100)
                    .message("Compendium ingestion failed").error(e.getMessage())
                    .build(), WebSocketMessage.MessageType.INGESTION_FAILED);
        }
    }

    // ─── Book registry operations ─────────────────────────────────────────

    public List<BookDto> getBooksByWorld(String worldId) {
        return bookRegistry.values().stream()
                .filter(b -> worldId.equals(b.getWorldId()))
                .toList();
    }

    public Optional<BookDto> getBook(String bookId) {
        return Optional.ofNullable(bookRegistry.get(bookId));
    }

    public void deleteBook(String bookId) {
        bookRegistry.remove(bookId);
        // TODO: also delete documents from vector store filtered by book_id == bookId
        log.info("Book {} removed from registry", bookId);
    }

    // ─── Rebuild registry on startup ──────────────────────────────────────

    @PostConstruct
    void rebuildBookRegistryFromOpenSearch() {
        try {
            log.info("Rebuilding book registry from OpenSearch…");
            SearchResponse<Void> response = openSearchClient.search(s -> s
                    .index("vector-store")
                    .size(0)
                    .aggregations("books", a -> a
                            .terms(t -> t.field("metadata.book_id").size(500))
                            .aggregations("world_id", sub -> sub.terms(t -> t.field("metadata.world_id").size(1)))
                            .aggregations("foundry_system", sub -> sub.terms(t -> t.field("metadata.foundry_system").size(1)))
                            .aggregations("book_title", sub -> sub.terms(t -> t.field("metadata.book_title").size(1)))
                            .aggregations("file_name", sub -> sub.terms(t -> t.field("metadata.file_name").size(1)))
                            .aggregations("entity_count", sub -> sub.filter(f -> f.term(t -> t.field("metadata.document_type").value(v -> v.stringValue("extracted_entity")))))
                    ), Void.class);

            var buckets = response.aggregations().get("books").sterms().buckets().array();
            for (var bucket : buckets) {
                String bookId = bucket.key();
                long totalChunks = bucket.docCount();
                String worldId = firstKey(bucket.aggregations().get("world_id").sterms());
                String foundrySystem = firstKey(bucket.aggregations().get("foundry_system").sterms());
                String bookTitle = firstKey(bucket.aggregations().get("book_title").sterms());
                String fileName = firstKey(bucket.aggregations().get("file_name").sterms());
                long entityCount = bucket.aggregations().get("entity_count").filter().docCount();

                BookDto book = BookDto.builder()
                        .bookId(bookId)
                        .worldId(worldId)
                        .foundrySystem(foundrySystem)
                        .bookTitle(bookTitle)
                        .fileName(fileName)
                        .sourceType(fileName != null && fileName.contains(".") ? "pdf" : "compendium")
                        .sourceId(fileName)
                        .chunkCount((int) totalChunks)
                        .entityCount((int) entityCount)
                        .status("COMPLETED")
                        .build();
                bookRegistry.put(bookId, book);
            }
            log.info("Rebuilt book registry: {} books found", bookRegistry.size());
        } catch (Exception e) {
            log.warn("Could not rebuild book registry from OpenSearch: {}", e.getMessage());
        }
    }

    private static String firstKey(org.opensearch.client.opensearch._types.aggregations.StringTermsAggregate agg) {
        var buckets = agg.buckets().array();
        return buckets.isEmpty() ? "unknown" : buckets.get(0).key();
    }

    // ─── private helpers ──────────────────────────────────────────────────

    private void enrichBaseMetadata(List<Document> docs, String bookId, String worldId,
                                    String foundrySystem, String bookTitle, String fileName) {
        for (Document doc : docs) {
            Map<String, Object> meta = doc.getMetadata();
            meta.put("book_id", bookId);
            meta.put("world_id", worldId);
            meta.put("foundry_system", foundrySystem);
            meta.put("book_title", bookTitle);
            meta.put("file_name", fileName);
            // game_system kept for backward compatibility
            meta.put("game_system", foundrySystem);
        }
    }

    private BookDto createBookRecord(String worldId, String foundrySystem, String bookTitle,
                                     String fileName, String sourceType, String sourceId) {
        String bookId = UUID.randomUUID().toString();
        BookDto book = BookDto.builder()
                .bookId(bookId)
                .worldId(worldId)
                .foundrySystem(foundrySystem)
                .bookTitle(bookTitle)
                .fileName(fileName)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .uploadDate(Instant.now())
                .status("UPLOADING")
                .build();
        bookRegistry.put(bookId, book);
        return book;
    }

    private Document toCompendiumDocument(CompendiumIngestionRequest.EntryDto entry,
                                          CompendiumIngestionRequest request,
                                          String bookId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("book_id", bookId);
        metadata.put("world_id", request.getWorldId());
        metadata.put("foundry_system", request.getFoundrySystem());
        metadata.put("book_title", "Compendium: " + (request.getPackLabel() != null ? request.getPackLabel() : request.getPackId()));
        metadata.put("file_name", request.getPackId());
        metadata.put("game_system", request.getFoundrySystem());
        metadata.put("source_type", "compendium");
        metadata.put("pack_id", request.getPackId());
        metadata.put("pack_label", request.getPackLabel());
        metadata.put("pack_document_type", request.getDocumentType());
        metadata.put("entry_id", entry.getId());
        metadata.put("entry_name", entry.getName());
        metadata.put("entry_type", entry.getType());
        if (entry.getMetadata() != null) {
            metadata.putAll(entry.getMetadata());
        }
        return new Document(entry.getText(), metadata);
    }

    private void completeStructuredIngestion(BookDto book, String sessionId, String foundrySystem,
                                             List<Document> classifiedChunks, List<Document> entityDocs) {
        String bookId = book.getBookId();
        String worldId = book.getWorldId();

        book.setStatus("STORING");
        for (Document doc : classifiedChunks) {
            doc.getMetadata().putIfAbsent("document_type", "raw_chunk");
        }

        List<Document> allDocuments = new ArrayList<>(classifiedChunks);
        allDocuments.addAll(entityDocs);

        sendProgress(sessionId, IngestionEvent.builder()
                .bookId(bookId).worldId(worldId).status("STORING").progress(80)
                .message("Storing " + allDocuments.size() + " documents in vector store…")
                .totalChunks(classifiedChunks.size()).entitiesExtracted(entityDocs.size())
                .build(), WebSocketMessage.MessageType.INGESTION_PROGRESS);

        storeWithRateLimit(allDocuments);

        try {
            log.info("[{}] Enriching system profile for {}", bookId, foundrySystem);
            systemProfileService.enrichFromIngestion(foundrySystem, classifiedChunks, entityDocs);
        } catch (Exception enrichErr) {
            log.warn("[{}] System profile enrichment failed (non-blocking): {}", bookId, enrichErr.getMessage());
        }

        book.setStatus("COMPLETED");
        book.setChunkCount(classifiedChunks.size());
        book.setEntityCount(entityDocs.size());

        sendProgress(sessionId, IngestionEvent.builder()
                .bookId(bookId).worldId(worldId).status("COMPLETED").progress(100)
                .message("Ingestion complete: " + classifiedChunks.size() + " chunks, " + entityDocs.size() + " entities")
                .totalChunks(classifiedChunks.size()).entitiesExtracted(entityDocs.size())
                .chunksProcessed(classifiedChunks.size())
                .build(), WebSocketMessage.MessageType.INGESTION_COMPLETED);

        log.info("[{}] Ingestion complete — {} chunks, {} entities", bookId, classifiedChunks.size(), entityDocs.size());
    }

    private void storeWithRateLimit(List<Document> documents) {
        long windowStart = System.currentTimeMillis();
        int requests = 0;
        int tokens = 0;

        for (Document doc : documents) {
            int docTokens = estimateTokens(doc.getText());
            long elapsed = System.currentTimeMillis() - windowStart;

            if (requests >= RPM_LIMIT || tokens + docTokens > TPM_LIMIT) {
                long sleep = WINDOW.toMillis() - elapsed;
                if (sleep > 0) {
                    log.debug("Rate-limit pause: {} ms", sleep);
                    try { Thread.sleep(sleep); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
                windowStart = System.currentTimeMillis();
                requests = 0;
                tokens = 0;
            }

            sendWithRetry(doc);
            requests++;
            tokens += docTokens;
        }
    }

    private void sendWithRetry(Document doc) {
        int attempt = 0;
        while (true) {
            try {
                vectorStore.add(List.of(doc));
                return;
            } catch (NonTransientAiException ex) {
                String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
                boolean retryable = msg.contains("429") || msg.contains("rate limit") || msg.contains("insufficient_quota");
                if (!retryable || attempt >= MAX_RETRIES) throw ex;
                attempt++;
                long backoff = (long) Math.min(60_000, Math.pow(2, attempt) * 1_000L);
                log.warn("Embedding throttled (attempt {}), backoff {} ms", attempt, backoff);
                try { Thread.sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / 4.0);
    }

    private void sendProgress(String sessionId, IngestionEvent event, WebSocketMessage.MessageType type) {
        if (sessionId == null) return;
        WebSocketMessage msg = WebSocketMessage.success(type, sessionId, event);
        webSocketController.sendIngestionUpdate(sessionId, msg);
    }
}
