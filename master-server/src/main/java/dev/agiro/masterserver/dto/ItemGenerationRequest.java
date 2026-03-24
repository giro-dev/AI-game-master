package dev.agiro.masterserver.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Request to generate items for a target compendium/pack.
 */
@Data
public class ItemGenerationRequest {

    /** Optional WS session to stream updates back to Foundry */
    private String sessionId;

    /** Optional client-side correlation ID */
    private String requestId;

    /** Free-text concept/prompt for the items */
    private String prompt;

    /** System/actor context (optional) */
    private String systemId;
    private String actorType;
    private String worldId;

    /** Target pack (Foundry compendium) like "world.items" */
    private String packId;

    /** Valid item types for this system (e.g. ["equipamiento", "arma"]) */
    private List<String> validItemTypes;

    /** Optional blueprint/skeleton describing allowed item fields */
    private Map<String, Object> blueprint;
}

