package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Compendium generated from extracted entities after book ingestion.
 * Groups entities by type (npc_stat_block, item_definition, spell_definition, etc.)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompendiumDto {
    private String bookId;
    private String bookTitle;
    private String foundrySystem;
    private int totalEntities;
    private Map<String, List<CompendiumEntry>> entitiesByType;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompendiumEntry {
        private String entityName;
        private String entityType;
        private String sourceChunkId;
        /** The raw JSON data extracted by the LLM */
        private Map<String, Object> data;
    }
}

