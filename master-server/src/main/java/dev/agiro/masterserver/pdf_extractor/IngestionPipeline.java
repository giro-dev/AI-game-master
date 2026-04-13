package dev.agiro.masterserver.pdf_extractor;

import dev.agiro.masterserver.config.GameMasterConfig;
import dev.agiro.masterserver.controller.WebSocketController;
import dev.agiro.masterserver.dto.BookDto;
import dev.agiro.masterserver.dto.CompendiumDto;
import dev.agiro.masterserver.dto.IngestionEvent;
import dev.agiro.masterserver.dto.WebSocketMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    private final dev.agiro.masterserver.tool.SystemProfileService systemProfileService;
    private final OpenSearchClient openSearchClient;
    private final GameMasterConfig gameMasterConfig;

    /** In-memory book registry (replace with a persistent store if needed) */
    private final Map<String, BookDto> bookRegistry = new ConcurrentHashMap<>();

    // ─── public API ───────────────────────────────────────────────────────

    /**
     * Start asynchronous ingestion of a PDF.
     *
     * @return bookId (UUID)
     */
    @Async
    public void ingestAsync(byte[] pdfBytes, String fileName, String worldId, String foundrySystem,
                            String bookTitle, String sessionId) {

        String bookId = UUID.randomUUID().toString();
        BookDto book = BookDto.builder()
                .bookId(bookId)
                .worldId(worldId)
                .foundrySystem(foundrySystem)
                .bookTitle(bookTitle)
                .fileName(fileName)
                .uploadDate(Instant.now())
                .status("UPLOADING")
                .build();
        bookRegistry.put(bookId, book);

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

            var ingestionCfg = gameMasterConfig.getIngestion();
            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(ingestionCfg.getChunkSize())
                    .withMinChunkSizeChars(ingestionCfg.getMinChunkSizeChars())
                    .withMinChunkLengthToEmbed(ingestionCfg.getMinChunkLengthToEmbed())
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

            // ── Stages 2–4: Classify → Extract → Store (per batch) ────
            book.setStatus("PROCESSING");
            sendProgress(sessionId, IngestionEvent.builder()
                    .bookId(bookId).worldId(worldId).status("PROCESSING").progress(30)
                    .message("Processing chunks (classify → extract → store)…")
                    .totalChunks(totalChunks)
                    .build(), WebSocketMessage.MessageType.INGESTION_PROGRESS);

            int batchSize = documentClassifier.getBatchSize();
            int totalEntities = 0;
            int chunksProcessed = 0;

            for (int i = 0; i < chunks.size(); i += batchSize) {
                List<Document> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));

                // 2a – classify this batch
                documentClassifier.classifyBatch(batch);

                // 2b – extract entities & collect documents to store
                List<Document> toStore = new ArrayList<>(batch.size() * 2);
                for (Document doc : batch) {
                    doc.getMetadata().putIfAbsent("document_type", "raw_chunk");
                    toStore.add(doc);

                    List<Document> entities = entityExtractor.extractFromDocument(doc);
                    toStore.addAll(entities);
                    totalEntities += entities.size();
                }

                // 2c – store this batch immediately
                storeWithRateLimit(toStore);
                chunksProcessed += batch.size();

                // progress: 30 → 80 proportionally
                int progress = 30 + (int) ((chunksProcessed / (double) totalChunks) * 50);
                sendProgress(sessionId, IngestionEvent.builder()
                        .bookId(bookId).worldId(worldId).status("PROCESSING").progress(progress)
                        .message("Processed " + chunksProcessed + "/" + totalChunks + " chunks (" + totalEntities + " entities)")
                        .totalChunks(totalChunks).entitiesExtracted(totalEntities).chunksProcessed(chunksProcessed)
                        .build(), WebSocketMessage.MessageType.INGESTION_PROGRESS);

                log.info("[{}] Batch {}/{} done — {} entities so far",
                        bookId, chunksProcessed, totalChunks, totalEntities);
            }

            log.info("[{}] Stages 2–4 complete: {} chunks processed, {} entities extracted",
                    bookId, chunksProcessed, totalEntities);

            // ── Stage 5: Enrich System Profile with new knowledge ───────
            try {
                log.info("[{}] Enriching system profile for {}", bookId, foundrySystem);
                systemProfileService.enrichFromIngestion(foundrySystem, List.of(), List.of());
            } catch (Exception enrichErr) {
                log.warn("[{}] System profile enrichment failed (non-blocking): {}", bookId, enrichErr.getMessage());
            }

            // ── Done ────────────────────────────────────────────────────
            book.setStatus("COMPLETED");
            book.setChunkCount(chunksProcessed);
            book.setEntityCount(totalEntities);

            sendProgress(sessionId, IngestionEvent.builder()
                    .bookId(bookId).worldId(worldId).status("COMPLETED").progress(100)
                    .message("Ingestion complete: " + chunksProcessed + " chunks, " + totalEntities + " entities")
                    .totalChunks(chunksProcessed).entitiesExtracted(totalEntities)
                    .chunksProcessed(chunksProcessed)
                    .build(), WebSocketMessage.MessageType.INGESTION_COMPLETED);

            log.info("[{}] Ingestion complete — {} chunks, {} entities", bookId, chunksProcessed, totalEntities);

            // ── Stage 6: Generate & send compendium ─────────────────────
            try {
                CompendiumDto compendium = getCompendium(bookId);
                log.info("[{}] Compendium generated: {} entities across {} types",
                        bookId, compendium.getTotalEntities(), compendium.getEntitiesByType().size());
                sendProgress(sessionId, compendium, WebSocketMessage.MessageType.INGESTION_COMPENDIUM);
            } catch (Exception compErr) {
                log.warn("[{}] Compendium generation failed (non-blocking): {}", bookId, compErr.getMessage());
            }

        } catch (Exception e) {
            log.error("[{}] Ingestion failed", book.getBookId(), e);
            book.setStatus("FAILED");
            sendProgress(sessionId, IngestionEvent.builder()
                    .bookId(book.getBookId()).worldId(worldId).status("FAILED").progress(100)
                    .message("Ingestion failed").error(e.getMessage())
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
        try {
            var result = openSearchClient.deleteByQuery(d -> d
                    .index("vector-store")
                    .query(q -> q
                            .term(t -> t
                                    .field("metadata.book_id")
                                    .value(v -> v.stringValue(bookId))
                            )
                    )
            );
            long deleted = result.deleted() != null ? result.deleted() : 0;
            log.info("Book {} removed — {} documents deleted from OpenSearch", bookId, deleted);
        } catch (Exception e) {
            log.error("Failed to delete documents for book {} from OpenSearch: {}", bookId, e.getMessage(), e);
            throw new RuntimeException("Failed to delete book data from vector store", e);
        }
    }

    /**
     * Build a compendium from all extracted entities for a given book.
     */
    public CompendiumDto getCompendium(String bookId) {
        BookDto book = bookRegistry.get(bookId);
        try {
            SearchResponse<Map> response = openSearchClient.search(s -> s
                    .index("vector-store")
                    .size(10_000)
                    .query(q -> q
                            .bool(b -> b
                                    .must(m -> m.term(t -> t.field("metadata.book_id").value(v -> v.stringValue(bookId))))
                                    .must(m -> m.term(t -> t.field("metadata.document_type").value(v -> v.stringValue("extracted_entity"))))
                            )
                    ), Map.class);

            Map<String, List<CompendiumDto.CompendiumEntry>> entitiesByType = new LinkedHashMap<>();
            ObjectMapper mapper = new ObjectMapper();

            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                if (source == null) continue;

                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) source.get("metadata");
                String content = (String) source.get("content");

                String entityType = metadata != null ? (String) metadata.getOrDefault("entity_type", "unknown") : "unknown";
                String entityName = metadata != null ? (String) metadata.getOrDefault("entity_name", "unknown") : "unknown";
                String sourceChunkId = metadata != null ? (String) metadata.get("source_chunk_id") : null;

                Map<String, Object> data;
                try {
                    data = mapper.readValue(content, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
                } catch (Exception e) {
                    data = Map.of("raw_text", content != null ? content : "");
                }

                CompendiumDto.CompendiumEntry entry = CompendiumDto.CompendiumEntry.builder()
                        .entityName(entityName)
                        .entityType(entityType)
                        .sourceChunkId(sourceChunkId)
                        .data(data)
                        .build();

                entitiesByType.computeIfAbsent(entityType, k -> new ArrayList<>()).add(entry);
            }

            int totalEntities = entitiesByType.values().stream().mapToInt(List::size).sum();

            return CompendiumDto.builder()
                    .bookId(bookId)
                    .bookTitle(book != null ? book.getBookTitle() : "unknown")
                    .foundrySystem(book != null ? book.getFoundrySystem() : "unknown")
                    .totalEntities(totalEntities)
                    .entitiesByType(entitiesByType)
                    .build();

        } catch (Exception e) {
            log.error("Failed to build compendium for book {}: {}", bookId, e.getMessage(), e);
            throw new RuntimeException("Failed to build compendium", e);
        }
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

    private void storeWithRateLimit(List<Document> documents) {
        if (!gameMasterConfig.getIngestion().isEnableRateLimit()) {
            log.debug("Rate limiting disabled — storing {} documents without throttling", documents.size());
            for (Document doc : documents) {
                sendWithRetry(doc);
            }
            return;
        }

        long windowStart = System.currentTimeMillis();
        int requests = 0;
        int tokens = 0;

        for (Document doc : documents) {
            log.atDebug().log("Embeding document {} of {}", requests, documents.size());
            int docTokens = estimateTokens(doc.getText());
            long elapsed = System.currentTimeMillis() - windowStart;

            if (requests >= gameMasterConfig.getIngestion().getRpmLimit() || tokens + docTokens > gameMasterConfig.getIngestion().getTpmLimit()) {
                long sleep = gameMasterConfig.getIngestion().getWindowMinutes() * 60_000L - elapsed;
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
                if (!retryable || attempt >= gameMasterConfig.getIngestion().getMaxRetries()) throw ex;
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

    private void sendProgress(String sessionId, Object payload, WebSocketMessage.MessageType type) {
        if (sessionId == null) return;
        WebSocketMessage msg = WebSocketMessage.success(type, sessionId, payload);
        webSocketController.sendIngestionUpdate(sessionId, msg);
    }
}

