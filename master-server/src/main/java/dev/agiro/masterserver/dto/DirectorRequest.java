package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Input to {@link dev.agiro.masterserver.service.AdventureDirectorService}.
 * Carries the transcribed player utterance plus all context needed to respond
 * narratively (world, system, optional confirmation reply for a previously
 * ambiguous turn).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DirectorRequest {
    private String transcription;
    private WorldStateDto worldState;
    private String adventureSessionId;
    private String worldId;
    private String foundrySystem;
    private String playerName;
    /** Set to "yes" / "no" / "rephrase" to answer a previous CONFIRMATION_NEEDED reply. */
    private String confirmationResponse;
}
