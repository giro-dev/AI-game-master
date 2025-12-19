package dev.agiro.masterserver.controller;

import dev.agiro.masterserver.dto.GameMasterRequest;
import dev.agiro.masterserver.dto.GameMasterResponse;
import dev.agiro.masterserver.dto.RagSearchResponse;
import dev.agiro.masterserver.service.GameMasterManualSolver;
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
public class GameMasterManualController {

    private final GameMasterManualSolver gameMasterManualSolver;

    public GameMasterManualController(GameMasterManualSolver gameMasterManualSolver) {
        this.gameMasterManualSolver = gameMasterManualSolver;
    }

    /**
     * Search for similar document chunks
     * GET /api/rag/search?query=how does attack of opportunity work&topK=5
     */
    @GetMapping("/resolve")
    public ResponseEntity<?> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(required = false) String foundrySystem,
            @RequestParam(required = false) String sourceDocument) {

        var response = gameMasterManualSolver.solveDoubt(query, foundrySystem);

        return ResponseEntity.ok(response);
    }
}

