package dev.agiro.masterserver.controller;

import dev.agiro.masterserver.dto.GameMasterRequest;
import dev.agiro.masterserver.dto.GameMasterResponse;
import dev.agiro.masterserver.service.GameMasterManualSolver;
import dev.agiro.masterserver.service.GameMasterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;

@Slf4j
@RestController
@RequestMapping("/gm")
@CrossOrigin(origins = "*")
public class GameMasterController {

    private final GameMasterService gameMasterService;
    private final GameMasterManualSolver manualSolver;

    public GameMasterController(GameMasterService gameMasterService,
                                GameMasterManualSolver manualSolver) {
        this.gameMasterService = gameMasterService;
        this.manualSolver = manualSolver;
    }

    @PostMapping(value = "/respond",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GameMasterResponse> handlePrompt(@RequestBody GameMasterRequest request) {
        log.atInfo().log("Received GameMasterRequest for token: {}", request.getTokenName());

        // Validate prompt
        if (request == null || request.getPrompt() == null || request.getPrompt().isBlank()) {
            GameMasterResponse errorResponse = new GameMasterResponse();
            errorResponse.setNarration("I didn't receive a valid prompt. Please try again.");
            errorResponse.setActions(new ArrayList<>());
            return ResponseEntity.badRequest().body(errorResponse);
        }

        try {
            // No token selected → general rulebook / manual query
            if (request.getTokenId() == null || request.getTokenId().isBlank()) {
                log.info("No token selected — routing to manual solver");
                String gameSystem = request.getFoundrySystem() != null ? request.getFoundrySystem() : "unknown";
                String answer = manualSolver.solveDoubt(request.getPrompt(), gameSystem);
                GameMasterResponse response = new GameMasterResponse();
                response.setNarration(answer);
                response.setActions(new ArrayList<>());
                return ResponseEntity.ok(response);
            }

            // Token selected → full game master processing with actions
            GameMasterResponse response = gameMasterService.processRequest(request);
            log.atInfo().log("Generated response for token {}: {}", request.getTokenName(), response.getNarration());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing request", e);
            GameMasterResponse errorResponse = new GameMasterResponse();
            errorResponse.setNarration("An error occurred while processing your request: " + e.getMessage());
            errorResponse.setActions(new ArrayList<>());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
}

