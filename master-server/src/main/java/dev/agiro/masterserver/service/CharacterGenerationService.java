package dev.agiro.masterserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.controller.WebSocketController;
import dev.agiro.masterserver.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CharacterGenerationService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final WebSocketController webSocketController;
    private final RAGService ragService;
    private final GameMasterManualSolver gameMasterManualSolver;

    private static final String CHARACTER_GENERATION_SYSTEM_PROMPT = """
            You are an expert character creator for tabletop RPG systems.
            You will receive:
            1. A character description/concept from the user
            2. A blueprint defining what fields MUST be filled for this system
            3. The target RPG system and actor type
            4. System-specific rules and guidance for creating this actor type
            
            Your job is to create a complete, valid character that:
            - Matches the user's description
            - Follows the system's rules and constraints
            - Fills in ALL fields from the actorFields array with appropriate values
            - Creates relevant items (skills, equipment, abilities, etc.)
            
            CRITICAL RULES FOR FILLING FIELDS:
            - You MUST fill EVERY field listed in the "actorFields" array
            - Each field has a "path" (e.g., "system.atributos.est") that shows where to place the value in the response
            - For nested paths like "system.atributos.est", create the nested structure: {"system": {"atributos": {"est": value}}}
            - Respect field types: "string" for text, "number" for integers, "resource" for numbers with min/max
            - Respect min/max constraints: never exceed the max value or go below the min value
            - For "resource" type fields, provide a number that respects the min/max range
            - For string fields like "concepto", "biografia", "descripcion": provide rich, detailed text
            - For numeric fields like attributes: distribute points wisely based on the character concept
            
            RESPONSE FORMAT:
            Respond ONLY with valid JSON matching this EXACT structure:
            {
              "character": {
                "actor": {
                  "name": "Character Name",
                  "type": "actorType",
                  "img": "icons/svg/mystery-man.svg",
                  "system": {
                    // ALL fields from actorFields must be filled here
                    // Use the exact nested structure from the field paths
                    // Example: for path "system.atributos.est", create: {"atributos": {"est": 5}}
                  }
                },
                "items": [
                  {
                    "name": "Item Name",
                    "type": "itemType",
                    "system": { ...item field values... }
                  }
                ]
              },
              "reasoning": "Brief explanation of design choices"
            }
            
            IMPORTANT:
            - Language: Generate all text in {language}
            - Be creative but mechanically sound
            - Balance attribute values appropriately (don't max everything out)
            - Provide detailed descriptions for text fields
            - Create 2-4 relevant items for the character
            """;

    private static final String CHARACTER_EXPLANATION_SYSTEM_PROMPT = """
            You are a Game Master who can read character sheets and describe them narratively.
            
            Given a character's complete data (attributes, items, etc.), provide a compelling narrative description that includes:
            - Physical appearance and personality
            - Background and motivations
            - Notable abilities and skills
            - Equipment and signature items
            - Role in an adventuring party
            
            Language: Respond in {language}
            Style: Write as a Game Master would describe a character to players
            Length: 2-3 paragraphs
            """;

    public CharacterGenerationService(ChatClient.Builder chatClientBuilder, 
                                      ObjectMapper objectMapper,
                                      WebSocketController webSocketController,
                                      RAGService ragService,
                                      GameMasterManualSolver gameMasterManualSolver) {
        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.8)
                        .build())
                .build();
        this.objectMapper = objectMapper;
        this.webSocketController = webSocketController;
        this.ragService = ragService;
        this.gameMasterManualSolver = gameMasterManualSolver;
    }

    /**
     * Generate a character from a prompt and blueprint
     */
    public CreateCharacterResponse generateCharacter(CreateCharacterRequest request) {
        return generateCharacter(request, null);
    }

    /**
     * Generate a character from a prompt and blueprint with WebSocket session
     */
    public CreateCharacterResponse generateCharacter(CreateCharacterRequest request, String sessionId) {
        log.info("Generating {} character: {}", request.getActorType(), request.getPrompt());

        String language = request.getLanguage() != null ? request.getLanguage() : "en";

        // Send WebSocket notification: generation started
        if (sessionId != null) {
            CharacterCreationEvent startEvent = CharacterCreationEvent.builder()
                    .requestId(sessionId)
                    .currentStep("Generating character...")
                    .progress(10)
                    .build();
            
            WebSocketMessage startMessage = WebSocketMessage.success(
                    WebSocketMessage.MessageType.CHARACTER_GENERATION_STARTED,
                    sessionId,
                    startEvent
            );
            webSocketController.sendCharacterUpdate(sessionId, startMessage);
        }

        // Build the user prompt with blueprint context
        String userPrompt = buildGenerationPrompt(request);

        try {
            // Call AI with structured output
            String responseJson = chatClient.prompt()
                    .system(sp -> sp.text(CHARACTER_GENERATION_SYSTEM_PROMPT.replace("{language}", language)))
                    .user(userPrompt)
                    .call()
                    .content();

            // Parse response
            log.debug("AI Response: {}", responseJson);

            // Clean response (remove markdown code blocks if present)
            responseJson = cleanJsonResponse(responseJson);

            // Parse the JSON response
            @SuppressWarnings("unchecked")
            Map<String, Object> aiResponse = objectMapper.readValue(responseJson, Map.class);

            CreateCharacterResponse response = new CreateCharacterResponse();
            response.setSuccess(true);

            // Extract character data
            Map<String, Object> characterData = (Map<String, Object>) aiResponse.get("character");
            if (characterData != null) {
                CreateCharacterResponse.CharacterDataDto characterDto =
                    objectMapper.convertValue(characterData, CreateCharacterResponse.CharacterDataDto.class);
                response.setCharacter(characterDto);
            }

            // Extract reasoning
            if (aiResponse.containsKey("reasoning")) {
                response.setReasoning((String) aiResponse.get("reasoning"));
            }

            log.info("Successfully generated character: {}",
                response.getCharacter().getActor().getName());

            // Send WebSocket notification: generation completed
            if (sessionId != null) {
                CharacterCreationEvent completedEvent = CharacterCreationEvent.builder()
                        .requestId(sessionId)
                        .characterData(response)
                        .characterName(response.getCharacter().getActor().getName())
                        .characterType(request.getActorType())
                        .currentStep("Character generated successfully!")
                        .progress(100)
                        .build();
                
                WebSocketMessage completedMessage = WebSocketMessage.success(
                        WebSocketMessage.MessageType.CHARACTER_GENERATION_COMPLETED,
                        sessionId,
                        completedEvent
                );
                webSocketController.sendCharacterUpdate(sessionId, completedMessage);
            }

            return response;

        } catch (Exception e) {
            log.error("Character generation failed", e);
            
            // Send WebSocket notification: generation failed
            if (sessionId != null) {
                WebSocketMessage errorMessage = WebSocketMessage.error(
                        WebSocketMessage.MessageType.CHARACTER_GENERATION_FAILED,
                        sessionId,
                        "Failed to generate character: " + e.getMessage()
                );
                webSocketController.sendCharacterUpdate(sessionId, errorMessage);
            }
            
            CreateCharacterResponse errorResponse = new CreateCharacterResponse();
            errorResponse.setSuccess(false);
            errorResponse.setReasoning("Failed to generate character: " + e.getMessage());
            return errorResponse;
        }
    }

    /**
     * Explain an existing character
     */
    public ExplainCharacterResponse explainCharacter(ExplainCharacterRequest request) {
        log.info("Explaining character for system: {}", request.getSystemId());

        try {
            String characterJson = objectMapper.writeValueAsString(request.getCharacterData());

            String userPrompt = String.format(
                "System: %s\n\nCharacter Data:\n%s\n\nProvide a narrative description of this character.",
                request.getSystemId(),
                characterJson
            );

            String explanation = chatClient.prompt()
                    .system(CHARACTER_EXPLANATION_SYSTEM_PROMPT.replace("{language}", "en"))
                    .user(userPrompt)
                    .call()
                    .content();

            ExplainCharacterResponse response = new ExplainCharacterResponse();
            response.setExplanation(explanation);

            return response;

        } catch (Exception e) {
            log.error("Character explanation failed", e);
            ExplainCharacterResponse errorResponse = new ExplainCharacterResponse();
            errorResponse.setExplanation("Failed to explain character: " + e.getMessage());
            return errorResponse;
        }
    }

    /**
     * Build the generation prompt with blueprint context and RAG-enhanced item information
     */
    private String buildGenerationPrompt(CreateCharacterRequest request) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("SYSTEM: ").append(request.getBlueprint().getSystemId()).append("\n");
        prompt.append("ACTOR TYPE: ").append(request.getActorType()).append("\n\n");

        prompt.append("USER REQUEST:\n");
        prompt.append(request.getPrompt()).append("\n\n");

        // Add GameMaster guidance for creating this actor type
        try {
            String actorTypeGuidance = gameMasterManualSolver.solveDoubt(
                String.format("How do I create a %s in this game system? What are the rules and important considerations?",
                    request.getActorType()),
                request.getBlueprint().getSystemId()
            );
            if (actorTypeGuidance != null && !actorTypeGuidance.isBlank()) {
                prompt.append("=== ACTOR CREATION GUIDANCE ===\n");
                prompt.append(actorTypeGuidance).append("\n\n");
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve actor type guidance from GameMaster manual", e);
        }

        // Add RAG context for character creation
        try {
            String characterContext = ragService.searchCharacterCreationContext(
                request.getPrompt(),
                request.getBlueprint().getSystemId(),
                3
            );
            if (!characterContext.isEmpty()) {
                prompt.append(characterContext).append("\n");
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve character creation context from RAG", e);
        }

        // Add RAG context for available item types
        if (request.getBlueprint().getAvailableItems() != null &&
            !request.getBlueprint().getAvailableItems().isEmpty()) {

            List<String> itemTypes = request.getBlueprint().getAvailableItems().stream()
                .map(item -> {
                    if (item instanceof Map) {
                        return (String) ((Map<?, ?>) item).get("type");
                    }
                    return null;
                })
                .filter(type -> type != null)
                .collect(Collectors.toList());

            if (!itemTypes.isEmpty()) {
                try {
                    String itemContext = ragService.searchItemContext(
                        itemTypes,
                        request.getBlueprint().getSystemId(),
                        2 // Top 2 chunks per item type
                    );
                    if (!itemContext.isEmpty()) {
                        prompt.append(itemContext).append("\n");
                    }
                } catch (Exception e) {
                    log.warn("Failed to retrieve item context from RAG", e);
                }
            }
        }

        prompt.append("BLUEPRINT:\n");
        try {
            String blueprintJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(request.getBlueprint());
            prompt.append(blueprintJson).append("\n\n");
        } catch (Exception e) {
            log.warn("Failed to serialize blueprint", e);
            prompt.append("[Blueprint serialization failed]\n\n");
        }

        prompt.append("IMPORTANT: You MUST fill ALL fields listed in the 'actorFields' array. ");
        prompt.append("For each field, use the 'path' property to determine where to place the value in the nested structure. ");
        prompt.append("For example, if path is 'system.atributos.est', create {\"system\": {\"atributos\": {\"est\": value}}}. ");
        prompt.append("Generate a complete character following the blueprint structure and constraints. ");
        prompt.append("Use the provided system rules and item information to create mechanically accurate items.");

        return prompt.toString();
    }

    /**
     * Clean JSON response from AI (remove markdown code blocks)
     */
    private String cleanJsonResponse(String response) {
        if (response == null) return "{}";

        // Remove markdown code blocks
        response = response.trim();
        if (response.startsWith("```json")) {
            response = response.substring(7);
        } else if (response.startsWith("```")) {
            response = response.substring(3);
        }

        if (response.endsWith("```")) {
            response = response.substring(0, response.length() - 3);
        }

        return response.trim();
    }
}

