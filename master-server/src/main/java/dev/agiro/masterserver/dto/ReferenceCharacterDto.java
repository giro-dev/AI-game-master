package dev.agiro.masterserver.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
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

    @JsonDeserialize(using = FlexibleTimestampDeserializer.class)
    private Long capturedAt;

    /** Handles both legacy ISO-8601 Instant strings and epoch-millis longs. */
    public static class FlexibleTimestampDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                return p.getLongValue();
            } else if (p.currentToken() == JsonToken.VALUE_STRING) {
                try {
                    return Instant.parse(p.getValueAsString()).toEpochMilli();
                } catch (Exception e) {
                    return System.currentTimeMillis();
                }
            }
            return null;
        }
    }
}

