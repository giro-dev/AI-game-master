package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A captured "reference character" — a real manually-created character
 * that serves as the structural template for AI generation.
 * Contains the exact actor data + embedded items so the AI knows
 * the precise format the game system expects.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceCharacterDto {

    private String systemId;
    private String actorType;
    private String label;           // e.g. the actor's name

    /** Full actor document (actor.toObject()) including system data */
    private Map<String, Object> actorData;

    /** All embedded items (actor.items.map(i => i.toObject())) */
    private List<Map<String, Object>> items;

    private Instant capturedAt;
}

