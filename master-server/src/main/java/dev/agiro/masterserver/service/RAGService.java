package dev.agiro.masterserver.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for Retrieval-Augmented Generation (RAG).
 * Searches the OpenSearch vector store with rich metadata filtering
 * (foundry_system, world_id, chunk_type, document_type, entity_type).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGService {

    private final VectorStore vectorStore;

    // ─── Item context (for character / item generation) ─────────────────

    /**
     * Search for relevant context about item types.
     */
    @Tool(description = "Search the rules vector store for item mechanics and descriptions matching the given item types and game system.")
    public String searchItemContext(List<String> itemTypes, String systemId, int topK) {
        return searchItemContext(itemTypes, systemId, null, topK);
    }

    public String searchItemContext(List<String> itemTypes, String systemId, String worldId, int topK) {
        if (itemTypes == null || itemTypes.isEmpty()) return "";
        log.info("Searching item context for {} types in system={}, world={}", itemTypes.size(), systemId, worldId);

        StringBuilder ctx = new StringBuilder("=== RELEVANT SYSTEM INFORMATION FROM RULES ===\n\n");
        for (String itemType : itemTypes) {
            try {
                String chunk = searchSingle(
                        "Rules and mechanics for %s. How does %s work?".formatted(itemType, itemType),
                        systemId, worldId, null, null, topK);
                if (!chunk.isEmpty()) {
                    ctx.append("--- ").append(itemType).append(" ---\n").append(chunk).append("\n\n");
                }
            } catch (Exception e) {
                log.warn("Failed to search context for item type '{}': {}", itemType, e.getMessage());
            }
        }
        return ctx.length() <= 50 ? "" : ctx.toString();
    }

    // ─── Character creation context ─────────────────────────────────────

    @Tool(description = "Search the rules vector store for character creation guidance matching the given concept and game system.")
    public String searchCharacterCreationContext(String characterConcept, String systemId, int topK) {
        return searchCharacterCreationContext(characterConcept, systemId, null, topK);
    }

    public String searchCharacterCreationContext(String characterConcept, String systemId, String worldId, int topK) {
        log.info("Searching character creation context for '{}' system={} world={}", characterConcept, systemId, worldId);
        try {
            // 1) Rules about character creation
            String rules = searchSingle(
                    "Character creation rules and guidelines: " + characterConcept,
                    systemId, worldId, "character_creation", null, topK);

            // 2) Extracted entity examples (character templates, classes, races)
            String examples = searchSingle(
                    "Character example template: " + characterConcept,
                    systemId, worldId, null, "extracted_entity", Math.max(2, topK / 2));

            StringBuilder ctx = new StringBuilder();
            if (!rules.isEmpty()) {
                ctx.append("=== CHARACTER CREATION RULES ===\n\n").append(rules).append("\n\n");
            }
            if (!examples.isEmpty()) {
                ctx.append("=== CHARACTER EXAMPLES FROM MANUALS ===\n\n").append(examples).append("\n\n");
            }
            if (ctx.isEmpty()) return "";
            log.info("Character creation context: {} chars", ctx.length());
            return ctx.toString();
        } catch (Exception e) {
            log.error("Failed to search character creation context", e);
            return "";
        }
    }

    // ─── Entity search (extracted structured data) ──────────────────────

    /**
     * Search for extracted entities of a specific type (weapon, spell, npc, etc.)
     */
    @Tool(description = "Search the vector store for extracted structured entities (weapons, spells, NPCs, etc.) filtered by game system, world, and entity type.")
    public String searchExtractedEntities(String query, String systemId, String worldId, String entityType, int topK) {
        log.info("Searching extracted entities: type={}, system={}, world={}", entityType, systemId, worldId);
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            FilterExpressionBuilder.Op filter = b.eq("document_type", "extracted_entity");
            if (systemId != null) filter = b.and(filter, b.eq("foundry_system", systemId));
            if (worldId != null) filter = b.and(filter, b.eq("world_id", worldId));
            if (entityType != null) filter = b.and(filter, b.eq("entity_type", entityType));

            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .filterExpression(filter.build())
                    .build());

            return formatDocuments(docs);
        } catch (Exception e) {
            log.warn("Entity search failed: {}", e.getMessage());
            return "";
        }
    }

    // ─── Bestiary / NPC search ──────────────────────────────────────────

    /**
     * Search bestiary and NPC stat blocks for NPC generation.
     */
    @Tool(description = "Search bestiary and NPC stat blocks for the given query, filtered by game system and world.")
    public String searchBestiaryContext(String query, String systemId, String worldId, int topK) {
        log.info("Searching bestiary context: system={}, world={}", systemId, worldId);
        try {
            // Raw bestiary/stat-block chunks
            String statBlocks = searchSingle(query, systemId, worldId, "npc_stat_block", null, topK);
            String bestiary = searchSingle(query, systemId, worldId, "bestiary", null, topK);
            // Extracted NPC entities
            String entities = searchExtractedEntities(query, systemId, worldId, "npc", Math.max(2, topK / 2));

            StringBuilder ctx = new StringBuilder();
            if (!statBlocks.isEmpty()) ctx.append("=== NPC STAT BLOCKS ===\n\n").append(statBlocks).append("\n\n");
            if (!bestiary.isEmpty()) ctx.append("=== BESTIARY LORE ===\n\n").append(bestiary).append("\n\n");
            if (!entities.isEmpty()) ctx.append("=== EXTRACTED NPC EXAMPLES ===\n\n").append(entities).append("\n\n");
            return ctx.toString();
        } catch (Exception e) {
            log.warn("Bestiary search failed: {}", e.getMessage());
            return "";
        }
    }

    // ─── Generic search (used by all specialised methods) ───────────────

    private String searchSingle(String query, String systemId, String worldId,
                                String chunkType, String documentType, int topK) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op filter = null;
        if (systemId != null && !systemId.isBlank()) {
            filter = b.eq("foundry_system", systemId);
        }
        if (worldId != null) {
            filter = filter == null ? b.eq("world_id", worldId) : b.and(filter, b.eq("world_id", worldId));
        }
        if (chunkType != null) {
            filter = filter == null ? b.eq("chunk_type", chunkType) : b.and(filter, b.eq("chunk_type", chunkType));
        }
        if (documentType != null) {
            filter = filter == null ? b.eq("document_type", documentType) : b.and(filter, b.eq("document_type", documentType));
        }

        SearchRequest.Builder searchBuilder = SearchRequest.builder()
                .query(query)
                .topK(topK);
        if (filter != null) {
            searchBuilder.filterExpression(filter.build());
        }

        List<Document> docs = vectorStore.similaritySearch(searchBuilder.build());
        return formatDocuments(docs);
    }

    private String formatDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) return "";
        return documents.stream()
                .map(doc -> {
                    StringBuilder sb = new StringBuilder();
                    Map<String, Object> meta = doc.getMetadata();
                    Object source = meta.get("book_title");
                    if (source == null) source = meta.get("file_name");
                    Object section = meta.get("section_title");
                    if (source != null) sb.append("[Source: ").append(source);
                    if (section != null) sb.append(" — ").append(section);
                    if (source != null) sb.append("]\n");
                    sb.append(doc.getText());
                    return sb.toString();
                })
                .collect(Collectors.joining("\n\n"));
    }
}

