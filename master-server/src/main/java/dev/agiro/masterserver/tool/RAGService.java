package dev.agiro.masterserver.tool;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG (Retrieval-Augmented Generation) service — wraps the Spring AI VectorStore
 * and OpenSearch to provide contextual document retrieval for all agents.
 * <p>
 * All queries are scoped by {@code foundry_system} metadata so agents only receive
 * context that is relevant to the active game system.
 */
@Slf4j
@Service
public class RAGService {

    private static final String VECTOR_INDEX = "vector-store";
    private static final int MIN_COMPENDIUM_TOP_K = 2;

    private final VectorStore vectorStore;
    private final OpenSearchClient openSearchClient;

    public RAGService(VectorStore vectorStore, OpenSearchClient openSearchClient) {
        this.vectorStore = vectorStore;
        this.openSearchClient = openSearchClient;
    }

    // ── Generic context search ──────────────────────────────────────────

    /**
     * General-purpose semantic search over all ingested documents for a given system.
     */
    public String searchContext(String query, String systemId, int topK) {
        return search(query, systemId, null, null, topK);
    }

    // ── Specialised searches ────────────────────────────────────────────

    /**
     * Search for character creation rules and steps.
     */
    public String searchCharacterCreationContext(String query, String systemId, int topK) {
        return search(query, systemId, "character_creation", null, topK);
    }

    /**
     * Search for character creation rules and compendium entities scoped to a world.
     */
    public String searchCharacterCreationContextWithCompendium(String query, String systemId, String worldId, int topK) {
        String rules = search(query, systemId, "character_creation", worldId, topK);
        String entities = searchExtractedEntities(query, systemId, worldId, null, compendiumTopK(topK));
        return mergeContexts(rules, entities);
    }

    /**
     * Search for extracted entities (stat blocks, items, spells…).
     */
    public String searchExtractedEntities(String query, String systemId, String worldId, String bookId, int topK) {
        String filterExpr = buildFilter(systemId, worldId, bookId, "extracted_entity");
        return searchWithFilter(query, filterExpr, topK);
    }

    /**
     * Search for item definitions and examples.
     */
    public String searchItemContext(String query, String systemId, int topK) {
        return search(query, systemId, "item_definition", null, topK);
    }

    /**
     * Search for item definitions and compendium entities scoped to a world.
     */
    public String searchItemContextWithCompendium(String query, String systemId, String worldId, int topK) {
        String itemDefinitions = search(query, systemId, "item_definition", worldId, topK);
        String entities = searchExtractedEntities(query, systemId, worldId, null, compendiumTopK(topK));
        return mergeContexts(itemDefinitions, entities);
    }

    /**
     * Search for rules and ruling chunks.
     */
    public String searchRulesContext(String query, String systemId, int topK) {
        return search(query, systemId, "rules", null, topK);
    }

    /**
     * Search for NPC / bestiary entries.
     */
    public String searchNpcContext(String query, String systemId, int topK) {
        return search(query, systemId, "npc_stat_block", null, topK);
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private String search(String query, String systemId, String chunkType, String worldId, int topK) {
        String filterExpr = buildFilter(systemId, worldId, null, chunkType);
        return searchWithFilter(query, filterExpr, topK);
    }

    private String searchWithFilter(String query, String filterExpr, int topK) {
        try {
            SearchRequest.Builder requestBuilder = SearchRequest.builder()
                    .query(query)
                    .topK(topK);

            if (filterExpr != null && !filterExpr.isBlank()) {
                requestBuilder.filterExpression(filterExpr);
            }

            List<Document> docs = vectorStore.similaritySearch(requestBuilder.build());

            if (docs == null || docs.isEmpty()) {
                return "";
            }

            return docs.stream()
                    .map(Document::getFormattedContent)
                    .collect(Collectors.joining("\n\n---\n\n"));

        } catch (Exception e) {
            log.debug("RAG search failed for query '{}': {}", query, e.getMessage());
            return "";
        }
    }

    /**
     * Build an OpenSearch-compatible filter expression for Spring AI's VectorStore.
     * Filters by system ID, optional world ID, optional book ID, and optional chunk/doc type.
     */
    private String buildFilter(String systemId, String worldId, String bookId, String typeField) {
        StringBuilder filter = new StringBuilder();

        if (systemId != null && !systemId.isBlank()) {
            filter.append("foundry_system == '").append(systemId).append("'");
        }

        if (worldId != null && !worldId.isBlank()) {
            if (!filter.isEmpty()) filter.append(" && ");
            filter.append("world_id == '").append(worldId).append("'");
        }

        if (bookId != null && !bookId.isBlank()) {
            if (!filter.isEmpty()) filter.append(" && ");
            filter.append("book_id == '").append(bookId).append("'");
        }

        if (typeField != null && !typeField.isBlank()) {
            if (!filter.isEmpty()) filter.append(" && ");
            // extracted_entity docs use "document_type"; classification docs use "chunk_type"
            if ("extracted_entity".equals(typeField)) {
                filter.append("document_type == 'extracted_entity'");
            } else {
                filter.append("chunk_type == '").append(typeField).append("'");
            }
        }

        return filter.toString();
    }

    /**
     * Low-level term search via OpenSearch REST client for non-vector queries
     * (e.g. fetching all documents of a specific type for a system).
     */
    public List<String> fetchDocumentsByTerm(String field, String value, int size) {
        try {
            SearchResponse<Map> response = openSearchClient.search(s -> s
                    .index(VECTOR_INDEX)
                    .size(size)
                    .query(q -> q.term(t -> t.field("metadata." + field).value(v -> v.stringValue(value)))),
                    Map.class);

            return response.hits().hits().stream()
                    .map(hit -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> source = hit.source();
                        if (source != null && source.get("content") instanceof String s) return s;
                        return "";
                    })
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.debug("OpenSearch term query failed for {}={}: {}", field, value, e.getMessage());
            return List.of();
        }
    }

    private String mergeContexts(String... contexts) {
        return java.util.Arrays.stream(contexts)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private int compendiumTopK(int topK) {
        return Math.max(MIN_COMPENDIUM_TOP_K, (topK + 1) / 2);
    }
}
