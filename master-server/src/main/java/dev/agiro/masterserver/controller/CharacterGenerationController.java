package dev.agiro.masterserver.controller;

import dev.agiro.masterserver.dto.CreateCharacterRequest;
import dev.agiro.masterserver.dto.CreateCharacterResponse;
import dev.agiro.masterserver.dto.ExplainCharacterRequest;
import dev.agiro.masterserver.dto.ExplainCharacterResponse;
import dev.agiro.masterserver.service.CharacterGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/gm/character")
@CrossOrigin(origins = "*")
public class CharacterGenerationController {

    private final CharacterGenerationService characterGenerationService;

    public CharacterGenerationController(CharacterGenerationService characterGenerationService) {
        this.characterGenerationService = characterGenerationService;
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
}
