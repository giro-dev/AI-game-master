package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Context about the Foundry VTT user who was speaking when an audio fragment was recorded.
 * Captured by the Foundry module and sent alongside the audio bytes so the backend can
 * attach speaker attribution to every transcript.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpeakerContextDto {

    // ── Foundry user ──────────────────────────────────────────────────────

    /** Foundry user ID (e.g. "AbCdEf123456") */
    private String userId;

    /** Foundry user display name */
    private String userName;

    /** Whether this user is the GM */
    private Boolean isGM;

    // ── Associated character ──────────────────────────────────────────────

    /** ID of the actor assigned to this user in the current world */
    private String characterId;

    /** Name of the actor assigned to this user */
    private String characterName;

    /** Actor type (e.g. "character", "npc") */
    private String characterType;

    // ── Session / world context ───────────────────────────────────────────

    /** Foundry world ID */
    private String worldId;

    /** Active scene name at the time of recording */
    private String sceneName;

    /** Foundry game system ID (e.g. "dnd5e", "pf2e") */
    private String systemId;

    /** WebSocket session ID for pushing transcription results back */
    private String sessionId;

    // ── AV source ────────────────────────────────────────────────────────

    /**
     * Which AV client detected the speech event.
     * e.g. "livekit", "dom-observer", "web-speech-api"
     * Informational only — used for debugging and analytics.
     */
    private String avSource;
}
