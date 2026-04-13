package dev.agiro.masterserver.controller;

import dev.agiro.masterserver.dto.SystemProfileDto;
import dev.agiro.masterserver.dto.SystemSnapshotDto;
import dev.agiro.masterserver.tool.SystemProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/system-profile")
@CrossOrigin(origins = "*")
public class SystemProfileController {

    private final SystemProfileService systemProfileService;

    public SystemProfileController(SystemProfileService systemProfileService) {
        this.systemProfileService = systemProfileService;
    }

    /**
     * Receive a system snapshot from the Foundry plugin and build/update the System Knowledge Profile.
     */
    @PostMapping(value = "/snapshot",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SystemProfileDto> processSnapshot(@RequestBody SystemSnapshotDto snapshot) {
        log.info("Received system snapshot: {} v{}", snapshot.getSystemId(), snapshot.getSystemVersion());

        if (snapshot.getSystemId() == null || snapshot.getSystemId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            SystemProfileDto profile = systemProfileService.processSnapshot(snapshot);
            return ResponseEntity.ok(profile);
        } catch (Exception e) {
            log.error("Failed to process system snapshot", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get the current System Knowledge Profile for a game system.
     */
    @GetMapping(value = "/{systemId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SystemProfileDto> getProfile(@PathVariable String systemId) {
        log.info("Getting system profile for: {}", systemId);

        return systemProfileService.getProfile(systemId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Force rebuild the profile for a system (e.g., after manual ingestion).
     */
    @PostMapping(value = "/{systemId}/rebuild", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SystemProfileDto> rebuildProfile(@PathVariable String systemId) {
        log.info("Rebuilding system profile for: {}", systemId);

        return systemProfileService.getProfile(systemId)
                .map(profile -> {
                    // Re-enrich from manuals
                    systemProfileService.enrichFromIngestion(systemId, List.of(), List.of());
                    return ResponseEntity.ok(systemProfileService.getProfile(systemId).orElse(profile));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}


