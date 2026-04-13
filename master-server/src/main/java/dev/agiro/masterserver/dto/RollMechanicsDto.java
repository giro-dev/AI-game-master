package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Roll mechanics detected from a game system.
 * Describes how dice rolls work in this system.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RollMechanicsDto {

    /** Primary dice formula (e.g. "1d20", "2d6", "Xd6") */
    private String formula;

    /** How success is determined */
    private SuccessModel successModel;

    /** Field path that feeds the primary modifier to rolls */
    private String modifierSource;

    /** Whether skills are embedded items (PF2e) or actor fields (D&D 5e) */
    private boolean skillAsItem;

    /** All detected dice formula patterns */
    private List<String> diceFormulas;

    /** Fields that trigger or participate in rolls */
    private List<RollTriggerField> rollTriggerFields;

    public enum SuccessModel {
        TARGET_NUMBER,  // Beat a DC (D&D, PF2e)
        COUNT_HITS,     // Count successes (Shadowrun, WoD)
        OPPOSED,        // Roll vs roll
        PBTA,           // 2d6 partial/full success (PbtA)
        UNKNOWN
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RollTriggerField {
        private String path;
        private String type;
        private String context;
    }
}

