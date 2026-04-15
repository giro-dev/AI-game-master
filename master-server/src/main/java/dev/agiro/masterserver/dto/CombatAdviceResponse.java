package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response from CombatAgent's live combat advice capability.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CombatAdviceResponse {

    /** Narrative description of the suggested action */
    private String narration;

    /** Short description of the chosen action type */
    private String suggestedAction;

    /** Rules-based reasoning behind the suggestion */
    private String reasoning;

    /** Foundry VTT actions to execute */
    private List<GameMasterResponse.ActionDto> actions;

    /** The ability chosen for the action (if any) */
    private String selectedAbilityId;
    private String selectedAbilityName;
}
