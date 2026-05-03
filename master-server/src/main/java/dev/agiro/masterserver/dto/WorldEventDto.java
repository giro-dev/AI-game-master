package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A timestamped world event stored in the campaign timeline.
 * Events form the persistent memory of the campaign, and are summarised
 * by the WorldAgent to provide context to all other agents.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorldEventDto {

    /** Unique identifier (auto-generated UUID) */
    private String eventId;

    /** World/campaign this event belongs to */
    private String worldId;

    /** WebSocket session that triggered this event (optional) */
    private String sessionId;

    private String title;
    private String description;

    /**
     * Semantic event type: "combat", "discovery", "political",
     * "natural", "player_action", "world_change".
     */
    private String eventType;

    /** Actor names involved in this event */
    private List<String> involvedActors;

    /** Location names involved */
    private List<String> involvedLocations;

    /** Faction names involved */
    private List<String> involvedFactions;

    /**
     * Importance level: "minor", "notable", "major", "world_changing".
     * Controls how prominently the event surfaces in world context summaries.
     */
    private String importance;

    /** Epoch-millis timestamp when the event occurred */
    private Long timestamp;
}
