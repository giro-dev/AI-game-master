package dev.agiro.masterserver.dto;

import lombok.Data;

@Data
public class CreateCharacterRequest {
    private String prompt;
    private String actorType;
    private String language;
    private String worldId;
    private CharacterBlueprintDto blueprint;
    private String sessionId; // Optional WebSocket session ID for progress updates
    private ReferenceCharacterDto referenceCharacter;
}
