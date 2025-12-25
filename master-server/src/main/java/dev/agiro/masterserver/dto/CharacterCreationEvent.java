package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Character creation event payload
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CharacterCreationEvent {

    /**
     * Unique identifier for this character generation request
     */
    private String requestId;

    /**
     * Generated character data
     */
    private CreateCharacterResponse characterData;

    /**
     * Character name
     */
    private String characterName;

    /**
     * Character type/class
     */
    private String characterType;

    /**
     * Path to character portrait (if generated)
     */
    private String portraitPath;

    /**
     * Path to token image (if generated)
     */
    private String tokenPath;

    /**
     * Progress percentage (0-100)
     */
    private Integer progress;

    /**
     * Current step description
     */
    private String currentStep;
}

