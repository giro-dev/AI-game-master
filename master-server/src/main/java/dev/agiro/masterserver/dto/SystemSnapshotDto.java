package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * DTO carrying the system snapshot from the Foundry VTT plugin.
 * Contains runtime introspection data that lets the AI learn any game system.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemSnapshotDto {

    private String systemId;
    private String systemVersion;
    private String systemTitle;
    private String foundryVersion;
    private String worldId;
    private Long timestamp;

    /** Actor/Item schemas extracted via runtime introspection */
    private SchemaData schemas;

    /** System CONFIG enums, type labels, status effects */
    private Map<String, Object> configData;

    /** Sample actors/items from system compendiums */
    private CompendiumSamples compendiumSamples;

    /** Sample actors/items from the world */
    private CompendiumSamples worldExamples;

    /** Numeric field distributions (min/max/avg) from existing actors */
    private Map<String, Map<String, ValueDistribution>> valueDistributions;

    /** System template.json data */
    private Map<String, Object> templateData;

    /** Hints from any active system adapter */
    private Map<String, Object> adapterHints;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SchemaData {
        private List<String> actorTypes;
        private List<String> itemTypes;
        private Map<String, SchemaEntry> actors;
        private Map<String, SchemaEntry> items;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SchemaEntry {
        private List<Map<String, Object>> fields;
        private Integer fieldCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompendiumSamples {
        private Map<String, List<Map<String, Object>>> actors;
        private Map<String, List<Map<String, Object>>> items;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValueDistribution {
        private Number min;
        private Number max;
        private Number avg;
        private Integer samples;
    }
}

