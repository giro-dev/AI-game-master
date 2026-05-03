package dev.agiro.masterserver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutations the AI Director wants applied to the {@link dev.agiro.masterserver.model.AdventureSession}
 * after a player turn.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StateUpdateDto {

    @Builder.Default
    private List<String> discoveredClues = new ArrayList<>();

    @Builder.Default
    private Map<String, String> npcDispositionChanges = new HashMap<>();

    /** Scene id to transition to, or {@code null} to stay in the current scene. */
    private String transitionTriggered;

    /** Signed delta applied to the current tension level, clamped server-side. */
    private int tensionDelta;
}
