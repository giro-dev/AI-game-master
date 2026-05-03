package dev.agiro.masterserver.controller;

import dev.agiro.masterserver.agent.combat.CombatAgent;
import dev.agiro.masterserver.dto.CombatAdviceRequest;
import dev.agiro.masterserver.dto.CombatAdviceResponse;
import dev.agiro.masterserver.dto.EncounterRequest;
import dev.agiro.masterserver.dto.EncounterResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoints for Phase 2 — Combat Agent.
 * <p>
 * <ul>
 *   <li>{@code POST /gm/combat/encounter} — design a balanced encounter (sync)</li>
 *   <li>{@code POST /gm/combat/encounter/async} — async variant; result pushed via WebSocket</li>
 *   <li>{@code POST /gm/combat/advise} — live combat advice for the active token</li>
 *   <li>{@code POST /gm/combat/loot} — distribute loot from a completed encounter</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/gm/combat")
@CrossOrigin(origins = "*")
public class CombatController {

    private final CombatAgent combatAgent;

    public CombatController(CombatAgent combatAgent) {
        this.combatAgent = combatAgent;
    }

    /**
     * Design a balanced encounter synchronously.
     * Use {@code /encounter/async} when a WebSocket session is available for streaming progress.
     */
    @PostMapping(value = "/encounter",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EncounterResponse> designEncounter(@RequestBody EncounterRequest request) {
        log.info("Encounter request: '{}' (party={} lvl{})", request.getPrompt(),
                request.getPartySize(), request.getPartyLevel());

        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            EncounterResponse err = new EncounterResponse();
            err.setSuccess(false);
            err.setReasoning("Prompt is required");
            return ResponseEntity.badRequest().body(err);
        }

        EncounterResponse response = combatAgent.designEncounter(request);
        return response.isSuccess()
                ? ResponseEntity.ok(response)
                : ResponseEntity.internalServerError().body(response);
    }

    /**
     * Fire-and-forget encounter design.
     * Progress events are pushed to {@code /queue/combat-{sessionId}} via WebSocket.
     */
    @PostMapping(value = "/encounter/async",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> designEncounterAsync(@RequestBody EncounterRequest request) {
        log.info("Async encounter request: '{}' session={}", request.getPrompt(), request.getSessionId());

        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        combatAgent.designEncounterAsync(request);
        return ResponseEntity.accepted().build();
    }

    /**
     * Provide live tactical advice for the currently active token.
     */
    @PostMapping(value = "/advise",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CombatAdviceResponse> adviseAction(@RequestBody CombatAdviceRequest request) {
        log.info("Combat advice for token '{}' in world '{}'",
                request.getActiveTokenName(), request.getWorldId());

        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            CombatAdviceResponse err = new CombatAdviceResponse();
            err.setNarration("Prompt is required");
            return ResponseEntity.badRequest().body(err);
        }

        CombatAdviceResponse response = combatAgent.adviseAction(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Distribute XP and loot from a completed encounter.
     * The encounter data must include the estimatedXp and recommendedLoot from the original generation.
     */
    @PostMapping(value = "/loot",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> distributeLoot(@RequestBody EncounterResponse encounter,
                                               @RequestParam(required = false) String worldId,
                                               @RequestParam(required = false) String sessionId,
                                               @RequestParam(required = false) String systemId) {
        log.info("Loot distribution for encounter '{}' session={}",
                encounter.getEncounterId(), sessionId);
        combatAgent.distributeLootAsync(encounter, worldId, sessionId, systemId);
        return ResponseEntity.accepted().build();
    }
}
