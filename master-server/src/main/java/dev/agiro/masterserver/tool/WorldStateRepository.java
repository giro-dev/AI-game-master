package dev.agiro.masterserver.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.FactionDto;
import dev.agiro.masterserver.dto.LocationResponse;
import dev.agiro.masterserver.dto.WorldEventDto;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.stream.Collectors;

/**
 * OpenSearch-backed persistence for Phase 3 world state documents:
 * {@link WorldEventDto} events, {@link FactionDto} factions, and
 * {@link LocationResponse} locations.
 * <p>
 * All documents are stored in three indices:
 * <ul>
 *   <li>{@code world-events}   — timeline events, keyed by {@code eventId}</li>
 *   <li>{@code world-factions} — faction documents, keyed by {@code factionId}</li>
 *   <li>{@code world-locations} — generated locations, keyed by {@code locationId}</li>
 * </ul>
 * Documents are scoped by {@code worldId} metadata for cross-campaign isolation.
 */
@Slf4j
@Repository
public class WorldStateRepository {

    static final String EVENTS_INDEX    = "world-events";
    static final String FACTIONS_INDEX  = "world-factions";
    static final String LOCATIONS_INDEX = "world-locations";

    private final OpenSearchClient openSearchClient;
    private final ObjectMapper objectMapper;

    public WorldStateRepository(OpenSearchClient openSearchClient, ObjectMapper objectMapper) {
        this.openSearchClient = openSearchClient;
        this.objectMapper = objectMapper;
        ensureIndices();
    }

    // ── Events ──────────────────────────────────────────────────────────

    public void saveEvent(WorldEventDto event) {
        if (event.getEventId() == null) event.setEventId(UUID.randomUUID().toString());
        if (event.getTimestamp() == null) event.setTimestamp(System.currentTimeMillis());
        indexDoc(EVENTS_INDEX, event.getEventId(), event);
    }

    /**
     * Returns the N most recent events for a world, ordered newest-first.
     */
    public List<WorldEventDto> findRecentEvents(String worldId, int limit) {
        return searchByWorldId(EVENTS_INDEX, worldId, limit, WorldEventDto.class);
    }

    /**
     * Returns events with importance "major" or "world_changing" for a world.
     */
    public List<WorldEventDto> findSignificantEvents(String worldId, int limit) {
        try {
            SearchResponse<Map> response = openSearchClient.search(s -> s
                    .index(EVENTS_INDEX)
                    .size(limit)
                    .query(q -> q.bool(b -> b
                            .must(m -> m.term(t -> t.field("worldId").value(v -> v.stringValue(worldId))))
                            .should(sh -> sh.term(t -> t.field("importance").value(v -> v.stringValue("major"))))
                            .should(sh -> sh.term(t -> t.field("importance").value(v -> v.stringValue("world_changing"))))
                            .minimumShouldMatch("1")
                    ))
                    .sort(o -> o.field(f -> f.field("timestamp").order(org.opensearch.client.opensearch._types.SortOrder.Desc))),
                    Map.class);

            return mapHits(response, WorldEventDto.class);
        } catch (Exception e) {
            log.debug("Significant events query failed for world '{}': {}", worldId, e.getMessage());
            return List.of();
        }
    }

    // ── Factions ─────────────────────────────────────────────────────────

    public void saveFaction(FactionDto faction) {
        if (faction.getFactionId() == null) {
            faction.setFactionId(faction.getWorldId() + ":" + slugify(faction.getName()));
        }
        faction.setLastUpdated(System.currentTimeMillis());
        indexDoc(FACTIONS_INDEX, faction.getFactionId(), faction);
    }

    public Optional<FactionDto> findFaction(String factionId) {
        return getById(FACTIONS_INDEX, factionId, FactionDto.class);
    }

    public List<FactionDto> findFactionsByWorld(String worldId) {
        return searchByWorldId(FACTIONS_INDEX, worldId, 100, FactionDto.class);
    }

    public void deleteFaction(String factionId) {
        deleteById(FACTIONS_INDEX, factionId);
    }

    // ── Locations ────────────────────────────────────────────────────────

    public void saveLocation(LocationResponse location) {
        if (location.getLocationId() == null) location.setLocationId(UUID.randomUUID().toString());
        indexDoc(LOCATIONS_INDEX, location.getLocationId(), location);
    }

    public Optional<LocationResponse> findLocation(String locationId) {
        return getById(LOCATIONS_INDEX, locationId, LocationResponse.class);
    }

    public List<LocationResponse> findLocationsByWorld(String worldId) {
        return searchByWorldId(LOCATIONS_INDEX, worldId, 50, LocationResponse.class);
    }

    // ── Generic internal helpers ─────────────────────────────────────────

    private <T> void indexDoc(String index, String id, T doc) {
        try {
            Map<String, Object> map = objectMapper.convertValue(doc, Map.class);
            IndexResponse r = openSearchClient.index(i -> i.index(index).id(id).document(map));
            log.debug("Indexed '{}' in '{}': result={}", id, index, r.result().jsonValue());
        } catch (Exception e) {
            log.warn("Failed to index '{}' in '{}': {}", id, index, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Optional<T> getById(String index, String id, Class<T> type) {
        try {
            var response = openSearchClient.get(g -> g.index(index).id(id), Map.class);
            if (!response.found() || response.source() == null) return Optional.empty();
            return Optional.of(objectMapper.convertValue(response.source(), type));
        } catch (Exception e) {
            log.debug("Get '{}' from '{}' failed: {}", id, index, e.getMessage());
            return Optional.empty();
        }
    }

    private void deleteById(String index, String id) {
        try {
            openSearchClient.delete(d -> d.index(index).id(id));
        } catch (Exception e) {
            log.warn("Delete '{}' from '{}' failed: {}", id, index, e.getMessage());
        }
    }

    private <T> List<T> searchByWorldId(String index, String worldId, int size, Class<T> type) {
        try {
            SearchResponse<Map> response = openSearchClient.search(s -> s
                    .index(index)
                    .size(size)
                    .query(q -> q.term(t -> t.field("worldId").value(v -> v.stringValue(worldId))))
                    .sort(o -> o.field(f -> f.field("timestamp")
                            .order(org.opensearch.client.opensearch._types.SortOrder.Desc))),
                    Map.class);
            return mapHits(response, type);
        } catch (Exception e) {
            log.debug("searchByWorldId({}, {}) failed: {}", index, worldId, e.getMessage());
            return List.of();
        }
    }

    private <T> List<T> mapHits(SearchResponse<Map> response, Class<T> type) {
        return response.hits().hits().stream()
                .map(Hit::source)
                .filter(Objects::nonNull)
                .map(s -> objectMapper.convertValue(s, type))
                .collect(Collectors.toList());
    }

    private void ensureIndices() {
        for (String index : List.of(EVENTS_INDEX, FACTIONS_INDEX, LOCATIONS_INDEX)) {
            try {
                boolean exists = openSearchClient.indices().exists(e -> e.index(index)).value();
                if (!exists) {
                    openSearchClient.indices().create(c -> c
                            .index(index)
                            .settings(s -> s.numberOfShards("1").numberOfReplicas("0")));
                    log.info("Created OpenSearch index '{}'", index);
                }
            } catch (Exception e) {
                log.warn("Could not ensure index '{}': {}", index, e.getMessage());
            }
        }
    }

    private static String slugify(String name) {
        if (name == null) return "unknown";
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }
}
