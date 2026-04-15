package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * A GM correction: the diff between AI-generated actor data and what the GM actually saved.
 * Posted from the Foundry module whenever a GM edits an AI-generated actor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CorrectionDto {

    /** Foundry game system ID (e.g. "dnd5e", "pf2e") */
    private String systemId;

    /** Actor type (e.g. "character", "npc") */
    private String actorType;

    /** The actor data as it was when the AI generated it */
    private Map<String, Object> generatedData;

    /** The actor data after the GM saved their edits */
    private Map<String, Object> editedData;

    /**
     * Dot-notation paths of fields that changed.
     * Derived client-side from Foundry's {@code updateActor} change delta.
     * Example: ["system.attributes.hp.value", "system.abilities.str.value"]
     */
    private List<String> changedPaths;

    /** Foundry user ID of the GM who made the edit */
    private String userId;

    /** WebSocket session ID (for result routing) */
    private String sessionId;

    /** Client-side epoch ms when the edit was committed */
    private Long timestamp;
}
