package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Response from CombatAgent's encounter design capability.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EncounterResponse {

    private boolean success;

    /** Client-side correlation ID for tracking */
    private String encounterId;

    private String title;
    private String description;

    /** List of combatant groups (enemies/neutrals) */
    private List<CombatantDto> combatants;

    /** Terrain and environmental details */
    private TerrainDto terrain;

    /** Estimated total XP value for the encounter */
    private Integer estimatedXp;

    /** Suggested loot for post-combat distribution */
    private List<Map<String, Object>> recommendedLoot;

    /** GM-facing design reasoning */
    private String reasoning;

    // ── Nested types ────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CombatantDto {
        private String name;
        /** Actor type in Foundry (e.g. "npc", "character") */
        private String actorType;
        /** How many of this combatant appear in the encounter */
        private Integer quantity;
        /** Challenge Rating or equivalent power level */
        private String cr;
        /** Stat block data compatible with the active system */
        private Map<String, Object> stats;
        /** Items (weapons, armor) this combatant carries */
        private List<Map<String, Object>> items;
        /** How this combatant behaves in combat */
        private String tactics;
        /** XP reward for defeating this combatant group */
        private Integer xpValue;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TerrainDto {
        private String type;
        private String description;
        private List<String> features;
        private List<String> hazards;
        private List<String> coverPositions;
    }
}
