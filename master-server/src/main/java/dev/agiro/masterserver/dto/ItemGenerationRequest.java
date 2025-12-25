package dev.agiro.masterserver.dto;

import lombok.Data;

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

    /** Target pack (Foundry compendium) like "world.items" */
    private String packId;

    /** Optional blueprint/skeleton describing allowed item fields */
    private Map<String, Object> blueprint;
}

