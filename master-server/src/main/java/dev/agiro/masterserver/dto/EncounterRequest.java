package dev.agiro.masterserver.dto;

import lombok.Data;

import java.util.List;

/**
 * Request to design a balanced encounter for a Foundry VTT session.
 */
@Data
public class EncounterRequest {

    /** WebSocket session ID for async progress updates */
    private String sessionId;

    /** World/campaign this encounter belongs to */
    private String worldId;

    /** Game system ID (e.g. "dnd5e", "pf2e") */
    private String systemId;

    /** Free-text description of the desired encounter */
    private String prompt;

    /** Average party level (used for CR balancing) */
    private Integer partyLevel;

    /** Number of player characters */
    private Integer partySize;

    /**
     * Desired difficulty: "easy", "medium", "hard", "deadly".
     * Defaults to "medium".
     */
    private String difficulty;

    /** Terrain/environment for tactical flavour */
    private String terrain;

    /** Optional list of creature types to include (e.g. ["undead", "humanoid"]) */
    private List<String> allowedCreatureTypes;

    /** Language for generated narration */
    private String language;
}
