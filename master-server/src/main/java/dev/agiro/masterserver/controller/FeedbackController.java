package dev.agiro.masterserver.controller;

import dev.agiro.masterserver.dto.CorrectionAckDto;
import dev.agiro.masterserver.dto.CorrectionDto;
import dev.agiro.masterserver.service.FeedbackService;
import dev.agiro.masterserver.tool.CorrectionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST endpoints for the GM feedback / correction loop.
 *
 * <p>The Foundry module POSTs here whenever the GM edits an AI-generated actor.
 * The server re-scores SemanticMap confidence and may schedule a re-extraction.
 */
@Slf4j
@RestController
@RequestMapping("/api/feedback")
@CrossOrigin(origins = "*")
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final CorrectionRepository correctionRepository;

    public FeedbackController(FeedbackService feedbackService,
                               CorrectionRepository correctionRepository) {
        this.feedbackService = feedbackService;
        this.correctionRepository = correctionRepository;
    }

    /**
     * Ingest a correction from the Foundry module.
     *
     * <pre>POST /api/feedback/correction</pre>
     */
    @PostMapping(value = "/correction",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CorrectionAckDto> submitCorrection(@RequestBody CorrectionDto correction) {
        log.info("Correction received: systemId={} changedPaths={}",
                correction.getSystemId(),
                correction.getChangedPaths() == null ? 0 : correction.getChangedPaths().size());

        if (correction.getSystemId() == null || correction.getSystemId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            CorrectionAckDto ack = feedbackService.ingestCorrection(correction);
            return ResponseEntity.ok(ack);
        } catch (Exception e) {
            log.error("Failed to process correction for '{}'", correction.getSystemId(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Retrieve recent corrections for a system (newest first, default up to 20).
     *
     * <pre>GET /api/feedback/{systemId}/recent?limit=20</pre>
     */
    @GetMapping(value = "/{systemId}/recent", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<CorrectionDto>> getRecentCorrections(
            @PathVariable String systemId,
            @RequestParam(defaultValue = "20") int limit) {

        List<CorrectionDto> corrections = correctionRepository.findBySystemId(systemId, limit);
        return ResponseEntity.ok(corrections);
    }

    /**
     * Count total corrections stored for a system.
     *
     * <pre>GET /api/feedback/{systemId}/count</pre>
     */
    @GetMapping(value = "/{systemId}/count", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Long> countCorrections(@PathVariable String systemId) {
        return ResponseEntity.ok(correctionRepository.countBySystemId(systemId));
    }
}
