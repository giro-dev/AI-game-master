package dev.agiro.masterserver.controller;

import dev.agiro.masterserver.dto.DirectorRequest;
import dev.agiro.masterserver.dto.DirectorResponse;
import dev.agiro.masterserver.model.AdventureModule;
import dev.agiro.masterserver.model.AdventureSession;
import dev.agiro.masterserver.repository.AdventureModuleRepository;
import dev.agiro.masterserver.repository.AdventureSessionRepository;
import dev.agiro.masterserver.service.AdventureDirectorService;
import dev.agiro.masterserver.service.AdventureIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/adventure")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class AdventureController {

    private final AdventureIngestionService ingestionService;
    private final AdventureModuleRepository moduleRepository;
    private final AdventureSessionRepository sessionRepository;
    private final AdventureDirectorService directorService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                      @RequestParam("foundrySystem") String foundrySystem,
                                                      @RequestParam(value = "worldId", required = false) String worldId,
                                                      @RequestParam(value = "title", required = false) String title) {
        if (file.isEmpty() || !MediaType.APPLICATION_PDF_VALUE.equals(file.getContentType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid file. Please upload a PDF."));
        }
        try {
            AdventureModule module = ingestionService.ingestPdf(file, foundrySystem, worldId, title);
            return ResponseEntity.ok(Map.of(
                    "id", module.getId(),
                    "title", module.getTitle(),
                    "system", module.getSystem(),
                    "actCount", module.getActs().size(),
                    "npcCount", module.getNpcs().size(),
                    "clueCount", module.getClues().size()
            ));
        } catch (IOException e) {
            log.error("Adventure ingestion failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{worldId}")
    public ResponseEntity<List<AdventureModule>> listForWorld(@PathVariable String worldId) {
        return ResponseEntity.ok(moduleRepository.findByWorldId(worldId));
    }

    @PostMapping("/{adventureId}/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable String adventureId,
                                                     @RequestParam(value = "worldId", required = false) String worldId) {
        AdventureModule module = moduleRepository.findById(adventureId).orElse(null);
        if (module == null) {
            return ResponseEntity.notFound().build();
        }

        String firstActId = module.getActs().isEmpty() ? null : module.getActs().get(0).getId();
        String firstSceneId = (firstActId == null || module.getActs().get(0).getScenes().isEmpty())
                ? null
                : module.getActs().get(0).getScenes().get(0).getId();

        AdventureSession session = AdventureSession.builder()
                .id("sess-" + UUID.randomUUID())
                .adventureModuleId(module.getId())
                .worldId(worldId != null ? worldId : module.getWorldId())
                .currentActId(firstActId)
                .currentSceneId(firstSceneId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        AdventureSession saved = sessionRepository.save(session);

        Map<String, Object> body = new HashMap<>();
        body.put("sessionId", saved.getId());
        body.put("adventureId", module.getId());
        body.put("currentActId", firstActId);
        body.put("currentSceneId", firstSceneId);
        if (firstSceneId != null) {
            module.getActs().get(0).getScenes().stream()
                    .filter(s -> s.getId().equals(firstSceneId))
                    .findFirst()
                    .ifPresent(s -> body.put("currentScene", Map.of(
                            "title", s.getTitle(),
                            "readAloudText", s.getReadAloudText() == null ? "" : s.getReadAloudText()
                    )));
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<AdventureSession> getSession(@PathVariable String sessionId) {
        return sessionRepository.findById(sessionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/session/{sessionId}/process")
    public ResponseEntity<DirectorResponse> process(@PathVariable String sessionId,
                                                    @RequestBody DirectorRequest request) {
        request.setAdventureSessionId(sessionId);
        return ResponseEntity.ok(directorService.process(request));
    }

    @PostMapping("/session/{sessionId}/confirm")
    public ResponseEntity<DirectorResponse> confirm(@PathVariable String sessionId,
                                                    @RequestBody Map<String, Object> body) {
        DirectorRequest req = new DirectorRequest();
        req.setAdventureSessionId(sessionId);
        req.setConfirmationResponse(body.getOrDefault("response", "no").toString());
        // Allow the client to provide updated context if useful
        Object playerName = body.get("playerName");
        if (playerName != null) req.setPlayerName(playerName.toString());
        Object foundrySystem = body.get("foundrySystem");
        if (foundrySystem != null) req.setFoundrySystem(foundrySystem.toString());
        Object worldId = body.get("worldId");
        if (worldId != null) req.setWorldId(worldId.toString());
        return ResponseEntity.ok(directorService.process(req));
    }
}
