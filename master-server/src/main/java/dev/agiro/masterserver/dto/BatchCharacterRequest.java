package dev.agiro.masterserver.dto;

import lombok.Data;

/**
 * Request to generate multiple characters in a single batch.
 * Each character uses the same blueprint and configuration but
 * receives a unique concept from the AI.
 */
@Data
public class BatchCharacterRequest {
    private String prompt;
    private String actorType;
    private String language;
    private String worldId;
    private CharacterBlueprintDto blueprint;
    private String sessionId;
    private ReferenceCharacterDto referenceCharacter;

    /** Number of characters to generate (1-10). */
    private int count = 1;

    /** Optional variation hint: "diverse", "similar", "themed". */
    private String variationMode;
}
