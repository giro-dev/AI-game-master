package dev.agiro.masterserver.dto;

import lombok.Data;

import java.util.List;

/**
 * Request for live combat advice during an active Foundry VTT combat encounter.
 */
@Data
public class CombatAdviceRequest {

    /** WebSocket session for per-campaign memory scoping */
    private String sessionId;

    /** World/campaign ID — used for memory scoping */
    private String worldId;

    /** Game system ID */
    private String systemId;

    /**
     * Optional conversation ID for memory scoping.
     * If null, falls back to worldId.
     */
    private String conversationId;

    /** Foundry token ID of the active combatant */
    private String activeTokenId;

    /** Display name of the active combatant */
    private String activeTokenName;

    /** Free-text GM request (e.g. "what should the goblin do this turn?") */
    private String prompt;

    /** Full current world/scene state */
    private WorldStateDto worldState;

    /** Available abilities for the active token */
    private List<AbilityDto> abilities;

    /** Optional explicit list of targeted token IDs */
    private List<String> targetTokenIds;
}
