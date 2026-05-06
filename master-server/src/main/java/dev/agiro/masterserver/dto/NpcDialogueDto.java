package dev.agiro.masterserver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single line of NPC dialogue, optionally with synthesized audio attached.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NpcDialogueDto {
    private String npcId;
    private String npcName;
    private String text;
    private String voiceId;
    /** Optional: "neutral" | "angry" | "scared" | "friendly" | "warning" | … */
    private String emotion;
    /**
     * URL to fetch the NPC dialogue WAV audio (e.g. {@code /audio/uuid.wav}).
     * Preferred over the base64 field to keep WebSocket messages small.
     */
    private String audioUrl;

    /** @deprecated Use {@link #audioUrl} instead. Kept for backwards compatibility. */
    @Deprecated
    private String audioBase64;
}
