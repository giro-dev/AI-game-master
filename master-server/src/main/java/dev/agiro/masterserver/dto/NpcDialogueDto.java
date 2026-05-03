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
    /** Base64-encoded WAV produced by the TTS service, populated server-side. */
    private String audioBase64;
}
