package dev.agiro.masterserver.dto;

import lombok.Data;

import java.util.Map;

/**
 * Request to validate character data against a blueprint's constraints
 * without generating anything. Used by the preview/edit flow.
 */
@Data
public class ValidateCharacterRequest {
    private String systemId;
    private String actorType;
    private Map<String, Object> characterData;
    private CharacterBlueprintDto blueprint;
}
