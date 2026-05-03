package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A faction in the persistent world state.
 * Factions are stored in OpenSearch and used as context injectors
 * for all agents operating in the same world.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactionDto {

    /** Unique identifier (systemId:worldId:factionName slug) */
    private String factionId;

    /** World/campaign this faction belongs to */
    private String worldId;

    private String name;
    private String description;

    /** Moral/ethical alignment (e.g. "lawful evil", "chaotic neutral") */
    private String alignment;

    /** Primary goals and motivations */
    private List<String> goals;

    /** Key resources and assets */
    private List<String> resources;

    /** Allied faction names */
    private List<String> allies;

    /** Enemy faction names */
    private List<String> enemies;

    /**
     * Current narrative status (e.g. "rising power", "weakened by recent defeat",
     * "covertly infiltrating the city guard").
     */
    private String currentStatus;

    private Long lastUpdated;
}
