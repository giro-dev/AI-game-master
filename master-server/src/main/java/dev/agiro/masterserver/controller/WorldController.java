package dev.agiro.masterserver.controller;

import dev.agiro.masterserver.agent.world.WorldAgent;
import dev.agiro.masterserver.dto.FactionDto;
import dev.agiro.masterserver.dto.LocationRequest;
import dev.agiro.masterserver.dto.LocationResponse;
import dev.agiro.masterserver.dto.WorldEventDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST endpoints for Phase 3 — World Agent.
 * <p>
 * <ul>
 *   <li>{@code POST /gm/world/location} — generate a location (sync)</li>
 *   <li>{@code POST /gm/world/location/async} — async variant with WebSocket progress</li>
 *   <li>{@code GET  /gm/world/{worldId}/locations} — list locations for a world</li>
 *   <li>{@code POST /gm/world/event} — log a world event (persisted + RAG-ingested)</li>
 *   <li>{@code GET  /gm/world/{worldId}/events} — recent significant events</li>
 *   <li>{@code POST /gm/world/faction} — create / update a faction</li>
 *   <li>{@code GET  /gm/world/{worldId}/factions} — list factions</li>
 *   <li>{@code DELETE /gm/world/faction/{factionId}} — delete a faction</li>
 *   <li>{@code GET  /gm/world/{worldId}/context} — full world context summary</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/gm/world")
@CrossOrigin(origins = "*")
public class WorldController {

    private final WorldAgent worldAgent;

    public WorldController(WorldAgent worldAgent) {
        this.worldAgent = worldAgent;
    }

    // ── Locations ─────────────────────────────────────────────────────────

    @PostMapping(value = "/location",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LocationResponse> generateLocation(@RequestBody LocationRequest request) {
        log.info("Location request: '{}' (world={})", request.getPrompt(), request.getWorldId());

        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            LocationResponse err = new LocationResponse();
            err.setSuccess(false);
            err.setReasoning("Prompt is required");
            return ResponseEntity.badRequest().body(err);
        }

        LocationResponse response = worldAgent.generateLocation(request);
        return response.isSuccess()
                ? ResponseEntity.ok(response)
                : ResponseEntity.internalServerError().body(response);
    }

    /**
     * Fire-and-forget location generation.
     * Results are pushed to {@code /queue/world-{sessionId}} via WebSocket.
     */
    @PostMapping(value = "/location/async",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> generateLocationAsync(@RequestBody LocationRequest request) {
        log.info("Async location request: '{}' session={}", request.getPrompt(), request.getSessionId());

        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        worldAgent.generateLocationAsync(request);
        return ResponseEntity.accepted().build();
    }

    @GetMapping(value = "/{worldId}/locations",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<LocationResponse>> getLocations(@PathVariable String worldId) {
        return ResponseEntity.ok(worldAgent.getLocations(worldId));
    }

    // ── Events ────────────────────────────────────────────────────────────

    @PostMapping(value = "/event",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorldEventDto> logEvent(@RequestBody WorldEventDto event) {
        log.info("World event: '{}' (world={}, importance={})",
                event.getTitle(), event.getWorldId(), event.getImportance());

        if (event.getTitle() == null || event.getTitle().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (event.getWorldId() == null || event.getWorldId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(worldAgent.logEvent(event));
    }

    @GetMapping(value = "/{worldId}/events",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<WorldEventDto>> getSignificantEvents(
            @PathVariable String worldId,
            @RequestParam(defaultValue = "20") int limit) {
        List<WorldEventDto> events = worldAgent.getWorldStateRepository().findSignificantEvents(worldId, limit);
        return ResponseEntity.ok(events);
    }

    // ── Factions ─────────────────────────────────────────────────────────

    @PostMapping(value = "/faction",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FactionDto> saveFaction(@RequestBody FactionDto faction) {
        log.info("Faction save: '{}' (world={})", faction.getName(), faction.getWorldId());

        if (faction.getName() == null || faction.getName().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (faction.getWorldId() == null || faction.getWorldId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(worldAgent.saveFaction(faction));
    }

    @GetMapping(value = "/{worldId}/factions",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<FactionDto>> getFactions(@PathVariable String worldId) {
        return ResponseEntity.ok(worldAgent.getFactions(worldId));
    }

    @PutMapping(value = "/faction",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FactionDto> updateFaction(@RequestBody FactionDto faction) {
        return ResponseEntity.ok(worldAgent.updateFaction(faction));
    }

    @DeleteMapping("/faction/{factionId}")
    public ResponseEntity<Void> deleteFaction(@PathVariable String factionId) {
        worldAgent.deleteFaction(factionId);
        return ResponseEntity.ok().build();
    }

    // ── World Context ─────────────────────────────────────────────────────

    @GetMapping(value = "/{worldId}/context",
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getWorldContext(@PathVariable String worldId) {
        String context = worldAgent.getWorldContext(worldId);
        return ResponseEntity.ok(context);
    }
}
