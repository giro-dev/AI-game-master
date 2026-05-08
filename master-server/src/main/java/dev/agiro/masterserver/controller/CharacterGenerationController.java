package dev.agiro.masterserver.controller;

import dev.agiro.masterserver.dto.*;
import dev.agiro.masterserver.service.CharacterGenerationService;
import dev.agiro.masterserver.service.SystemProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@Slf4j
@RestController
@RequestMapping("/gm/character")
@CrossOrigin(origins = "*")
public class CharacterGenerationController {

    private final CharacterGenerationService characterGenerationService;
    private final SystemProfileService systemProfileService;

    public CharacterGenerationController(CharacterGenerationService characterGenerationService,
                                         SystemProfileService systemProfileService) {
        this.characterGenerationService = characterGenerationService;
        this.systemProfileService = systemProfileService;
    }

    /**
     * Generate a character from a prompt and blueprint
     */
    @PostMapping(value = "/generate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreateCharacterResponse> generateCharacter(@RequestBody CreateCharacterRequest request) {
        log.info("Character generation request: {} for {}", request.getPrompt(), request.getActorType());

        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            CreateCharacterResponse errorResponse = new CreateCharacterResponse();
            errorResponse.setSuccess(false);
            errorResponse.setReasoning("Prompt is required");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        if (request.getBlueprint() == null) {
            CreateCharacterResponse errorResponse = new CreateCharacterResponse();
            errorResponse.setSuccess(false);
            errorResponse.setReasoning("Blueprint is required");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        try {
            CreateCharacterResponse response = characterGenerationService.generateCharacter(
                    request,
                    request.getSessionId()
            );

            if (response.getSuccess()) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.internalServerError().body(response);
            }
        } catch (Exception e) {
            log.error("Character generation failed", e);
            CreateCharacterResponse errorResponse = new CreateCharacterResponse();
            errorResponse.setSuccess(false);
            errorResponse.setReasoning("Internal error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Explain an existing character
     */
    @PostMapping(value = "/explain",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExplainCharacterResponse> explainCharacter(@RequestBody ExplainCharacterRequest request) {
        log.info("Character explanation request for system: {}", request.getSystemId());

        if (request.getCharacterData() == null) {
            ExplainCharacterResponse errorResponse = new ExplainCharacterResponse();
            errorResponse.setExplanation("Character data is required");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        try {
            ExplainCharacterResponse response = characterGenerationService.explainCharacter(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Character explanation failed", e);
            ExplainCharacterResponse errorResponse = new ExplainCharacterResponse();
            errorResponse.setExplanation("Failed to explain character: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    // ── Reference Character Introspection ───────────────────────────────

    /**
     * Store a reference character (captured from a manually-created character in Foundry).
     * This becomes the structural template for AI character generation.
     */
    @PostMapping(value = "/reference",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReferenceCharacterDto> storeReferenceCharacter(@RequestBody ReferenceCharacterDto request) {
        log.info("Storing reference character '{}' for system={}, actorType={}",
                request.getLabel(), request.getSystemId(), request.getActorType());

        if (request.getSystemId() == null || request.getActorType() == null) {
            return ResponseEntity.badRequest().build();
        }

        request.setCapturedAt(Instant.now().toEpochMilli());
        systemProfileService.storeReferenceCharacter(request);

        return ResponseEntity.ok(request);
    }

    /**
     * Retrieve the stored reference character for a given system + actor type.
     */
    @GetMapping(value = "/reference/{systemId}/{actorType}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReferenceCharacterDto> getReferenceCharacter(
            @PathVariable String systemId,
            @PathVariable String actorType) {
        return systemProfileService.getReferenceCharacter(systemId, actorType)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete the stored reference character for a given system + actor type.
     */
    @DeleteMapping(value = "/reference/{systemId}/{actorType}")
    public ResponseEntity<Void> deleteReferenceCharacter(
            @PathVariable String systemId,
            @PathVariable String actorType) {
        systemProfileService.deleteReferenceCharacter(systemId, actorType);
        return ResponseEntity.ok().build();
    }

    // ── Batch Generation ─────────────────────────────────────────────────

    /**
     * Generate multiple characters in a single batch request.
     * Each character gets a unique concept based on the shared prompt.
     */
    @PostMapping(value = "/generate/batch",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BatchCharacterResponse> generateBatch(@RequestBody BatchCharacterRequest request) {
        log.info("Batch generation request: {} x{} for {}", request.getPrompt(), request.getCount(), request.getActorType());

        int count = Math.max(1, Math.min(10, request.getCount()));
        BatchCharacterResponse batchResponse = new BatchCharacterResponse();
        batchResponse.setRequested(count);

        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            batchResponse.setSuccess(false);
            batchResponse.setReasoning("Prompt is required");
            return ResponseEntity.badRequest().body(batchResponse);
        }

        if (request.getBlueprint() == null) {
            batchResponse.setSuccess(false);
            batchResponse.setReasoning("Blueprint is required");
            return ResponseEntity.badRequest().body(batchResponse);
        }

        String variationHint = request.getVariationMode() != null ? request.getVariationMode() : "diverse";

        for (int i = 0; i < count; i++) {
            try {
                // Append variation hint to the prompt for diversity
                String variedPrompt = count > 1
                        ? request.getPrompt() + String.format(
                            "\n\n[Variation %d of %d. Mode: %s. Create a DISTINCT character different from previous ones.]",
                            i + 1, count, variationHint)
                        : request.getPrompt();

                CreateCharacterRequest singleRequest = new CreateCharacterRequest();
                singleRequest.setPrompt(variedPrompt);
                singleRequest.setActorType(request.getActorType());
                singleRequest.setLanguage(request.getLanguage());
                singleRequest.setWorldId(request.getWorldId());
                singleRequest.setBlueprint(request.getBlueprint());
                singleRequest.setSessionId(request.getSessionId());
                singleRequest.setReferenceCharacter(request.getReferenceCharacter());

                CreateCharacterResponse result = characterGenerationService.generateCharacter(
                        singleRequest, request.getSessionId());

                if (result.getSuccess()) {
                    batchResponse.getCharacters().add(result);
                } else {
                    batchResponse.getErrors().add(
                            String.format("Character %d: %s", i + 1, result.getReasoning()));
                }
            } catch (Exception e) {
                log.error("Batch generation failed for character {}", i + 1, e);
                batchResponse.getErrors().add(
                        String.format("Character %d: %s", i + 1, e.getMessage()));
            }
        }

        batchResponse.setGenerated(batchResponse.getCharacters().size());
        batchResponse.setSuccess(batchResponse.getGenerated() > 0);
        batchResponse.setReasoning(String.format("Generated %d of %d characters",
                batchResponse.getGenerated(), count));

        return ResponseEntity.ok(batchResponse);
    }

    // ── Validation Endpoint ──────────────────────────────────────────────

    /**
     * Validate character data against blueprint constraints.
     * Used by the preview/edit flow to show real-time validation.
     */
    @PostMapping(value = "/validate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ValidateCharacterResponse> validateCharacter(@RequestBody ValidateCharacterRequest request) {
        log.info("Validation request for system={}, actorType={}", request.getSystemId(), request.getActorType());

        ValidateCharacterResponse response = new ValidateCharacterResponse();

        if (request.getCharacterData() == null) {
            response.setValid(false);
            response.getErrors().add(new ValidateCharacterResponse.ValidationError(
                    "characterData", "Character data is required", "error"));
            return ResponseEntity.badRequest().body(response);
        }

        try {
            // Validate against blueprint constraints
            if (request.getBlueprint() != null && request.getBlueprint().getActorFields() != null) {
                characterGenerationService.validateAgainstBlueprint(
                        request.getCharacterData(), request.getBlueprint(), response);
            }

            response.setValid(response.getErrors().isEmpty());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Validation failed", e);
            response.setValid(false);
            response.getErrors().add(new ValidateCharacterResponse.ValidationError(
                    "internal", "Validation error: " + e.getMessage(), "error"));
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
