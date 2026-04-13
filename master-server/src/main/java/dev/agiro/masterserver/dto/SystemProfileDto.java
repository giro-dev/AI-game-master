package dev.agiro.masterserver.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * The System Knowledge Profile: the AI's "learned" understanding of a Foundry VTT game system.
 * Built from runtime snapshots + ingested manual knowledge.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemProfileDto {

    private String systemId;
    private String systemVersion;
    private String systemTitle;
    @JsonDeserialize(using = ReferenceCharacterDto.FlexibleTimestampDeserializer.class)
    private Long lastUpdated;

    /** AI-inferred semantic field groups for this system */
    private List<FieldGroup> fieldGroups;

    /** Detected constraints (point budgets, ranges, mandatory fields) */
    private List<DetectedConstraint> detectedConstraints;

    /** Character creation steps extracted from manuals */
    private List<String> characterCreationSteps;

    /** Available races/classes/archetypes for character creation */
    private Map<String, List<String>> creationChoices;

    /** Narrative/identity field paths (name, bio, description) */
    private List<String> identityFields;

    /** Combat/mechanical field paths */
    private List<String> mechanicalFields;

    /** Sample value ranges per field */
    private Map<String, ValueRange> valueRanges;

    /** Summary of the system for prompt injection */
    private String systemSummary;

    /** Whether this profile has been enriched from ingested manuals */
    private boolean enrichedFromManuals;

    /** Semantic mapping of system fields to universal RPG concepts */
    private SemanticMapDto semanticMap;

    /** Detected roll mechanics for this system */
    private RollMechanicsDto rollMechanics;

    /** Overall confidence of the semantic mapping (0.0 to 1.0) */
    private Double confidence;

    /** Example actor data used for few-shot generation */
    private List<Map<String, Object>> actorExamples;

    /** Example item data used for few-shot generation */
    private List<Map<String, Object>> itemExamples;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldGroup {
        private String name;
        private String description;
        private List<String> fieldPaths;
        private String category; // "identity", "attributes", "skills", "combat", "resources", "other"
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DetectedConstraint {
        private String type; // "point_budget", "range", "required", "enum"
        private String fieldPath;
        private String description;
        private Map<String, Object> parameters; // min, max, total, choices, etc.
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValueRange {
        private Number min;
        private Number max;
        private Number typical;
        private String fieldType;
    }
}

