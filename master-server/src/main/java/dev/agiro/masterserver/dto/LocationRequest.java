package dev.agiro.masterserver.dto;

import lombok.Data;

import java.util.List;

/**
 * Request to generate a location (dungeon, city district, wilderness, etc.)
 * for a Foundry VTT session. Result is persisted in OpenSearch for
 * cross-session continuity (Phase 3 world state).
 */
@Data
public class LocationRequest {

    /** WebSocket session ID for async progress updates */
    private String sessionId;

    /** World/campaign this location belongs to */
    private String worldId;

    /** Game system ID */
    private String systemId;

    /** Free-text description of the desired location */
    private String prompt;

    /**
     * Location type: "dungeon", "lair", "city_district", "wilderness",
     * "building", "ruins", "planar".
     */
    private String locationType;

    /**
     * Relative size: "small" (3–5 rooms), "medium" (6–10), "large" (11–20).
     */
    private String size;

    /** Party level — used to scale encounters and traps */
    private Integer partyLevel;

    /** Language for generated narration */
    private String language;

    /** Optional faction to associate with this location */
    private String controllingFaction;
}
