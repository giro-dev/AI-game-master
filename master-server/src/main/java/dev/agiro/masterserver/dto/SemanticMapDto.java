package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Semantic mapping of system-specific field paths to universal RPG concepts.
 * This is the critical abstraction layer that enables system-agnostic generation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticMapDto {

    private FieldMapping health;
    private FieldMapping healthSecondary;
    private FieldMapping level;
    private FieldMapping experience;
    private List<FieldMapping> primaryStats;
    private List<FieldMapping> skills;
    private FieldMapping rollAttribute;
    private List<FieldMapping> currency;
    private FieldMapping initiative;
    private FieldMapping armorClass;
    private FieldMapping movementSpeed;

    /**
     * Maps a single system field to a universal concept.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldMapping {
        /** Dot-notation path in actor.system (e.g. "system.attributes.hp.value") */
        private String path;
        /** The value type: "number", "string", "boolean", "object" */
        private String type;
        /** Observed value range [min, max] */
        private double[] range;
        /** Whether this field is required for valid actor creation */
        private boolean required;
        /** The universal concept this field maps to */
        private SemanticConcept inferredAs;
        /** Confidence of the mapping (0.0 to 1.0) */
        private double confidence;
    }

    /**
     * Universal RPG concepts that field paths can be mapped to.
     */
    public enum SemanticConcept {
        HEALTH,
        HEALTH_SECONDARY,
        LEVEL,
        EXPERIENCE,
        STAT_STRENGTH,
        STAT_DEXTERITY,
        STAT_CONSTITUTION,
        STAT_INTELLIGENCE,
        STAT_WISDOM,
        STAT_CHARISMA,
        STAT_GENERIC,
        SKILL_RANK,
        ROLL_ATTRIBUTE,
        CURRENCY,
        INITIATIVE,
        ARMOR_CLASS,
        DAMAGE_FORMULA,
        ACTION_TRIGGER,
        MOVEMENT_SPEED,
        SAVING_THROW,
        SPELL_SLOTS,
        PROFICIENCY,
        BIOGRAPHY,
        UNKNOWN
    }
}

