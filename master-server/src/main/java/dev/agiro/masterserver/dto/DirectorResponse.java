package dev.agiro.masterserver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.agiro.masterserver.dto.GameMasterResponse.ActionDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Output of {@link dev.agiro.masterserver.service.AdventureDirectorService}.
 *
 * <p>Two flavours:
 * <ol>
 *   <li><b>Confirmation</b>: {@code confirmationNeeded == true} and
 *       {@code confirmationQuestion} contains a question the GM/UI must surface
 *       before any state mutation happens.</li>
 *   <li><b>Director turn</b>: narration + NPC dialogues + VTT actions + state
 *       updates that have already been persisted to the
 *       {@link dev.agiro.masterserver.model.AdventureSession}.</li>
 * </ol>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DirectorResponse {
    private String narration;

    /**
     * URL to fetch the narration WAV audio (e.g. {@code /audio/uuid.wav}).
     * Preferred over the base64 field to keep WebSocket messages small.
     */
    private String narrationAudioUrl;

    /** @deprecated Use {@link #narrationAudioUrl} instead. Kept for backwards compatibility. */
    @Deprecated
    private String narrationAudioBase64;

    @Builder.Default
    private List<NpcDialogueDto> npcDialogues = new ArrayList<>();

    @Builder.Default
    private List<ActionDto> actions = new ArrayList<>();

    private StateUpdateDto stateUpdates;

    private String reasoning;

    private String confirmationQuestion;

    private boolean confirmationNeeded;
}
