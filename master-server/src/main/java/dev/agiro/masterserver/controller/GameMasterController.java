package dev.agiro.masterserver.controller;

import dev.agiro.masterserver.dto.GameMasterRequest;
import dev.agiro.masterserver.dto.GameMasterResponse;
import dev.agiro.masterserver.dto.RagSearchResponse;
import dev.agiro.masterserver.service.GameMasterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/gm")
@CrossOrigin(origins = "*") // Allow Foundry VTT to call this endpoint
public class GameMasterController {

    private final GameMasterService gameMasterService;

    public GameMasterController(GameMasterService gameMasterService) {
        this.gameMasterService = gameMasterService;
    }

    @PostMapping(value = "/respond",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GameMasterResponse> handlePrompt(@RequestBody GameMasterRequest request) {
        log.atInfo().log( "Received GameMasterRequest for token : {}", request.getTokenName());
        // Validate request
        if (request == null || request.getPrompt() == null || request.getPrompt().isBlank()) {
            GameMasterResponse errorResponse = new GameMasterResponse();
            errorResponse.setNarration("I didn't receive a valid prompt. Please try again.");
            errorResponse.setActions(new ArrayList<>());
            return ResponseEntity.badRequest().body(errorResponse);
        }

        if (request.getTokenId() == null || request.getTokenId().isBlank()) {
            GameMasterResponse errorResponse = new GameMasterResponse();
            errorResponse.setNarration("No token selected. Please select a token and try again.");
            errorResponse.setActions(new ArrayList<>());
            return ResponseEntity.badRequest().body(errorResponse);
        }

        try {
            GameMasterResponse response = gameMasterService.processRequest(request);
            log.atInfo().log("Generated response for token {}: {}", request.getTokenName(), response.getNarration());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            GameMasterResponse errorResponse = new GameMasterResponse();
            errorResponse.setNarration("An error occurred while processing your request: " + e.getMessage());
            errorResponse.setActions(new ArrayList<>());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
}

