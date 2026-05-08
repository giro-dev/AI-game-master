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
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CharacterGenerationService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final WebSocketController webSocketController;

    // Specialized agents: concept, fields and items
    private final ConceptAgent conceptAgent;
    private final FieldFillerAgent fieldFillerAgent;
    private final ItemGenerationAgent itemGenerationAgent;

    // Fallback prompts used only when no System Profile is available
    private static final String FALLBACK_CORE_CONCEPT_PROMPT = """
            You are an expert character creator for tabletop RPG systems.
            
            Create a core concept for a character based on the user's description.
            Generate ONLY the essential identity fields.
            
            Respond ONLY with valid JSON:
            {
              "name": "Character Name",
              "concept": "Brief concept (2-3 sentences)",
              "biography": "Background story (3-4 sentences)",
              "description": "Physical/personality description (2-3 sentences)"
            }
            
            Language: {language}
            Be creative and evocative. This concept will be used to fill other fields.
            """;

    private static final String FALLBACK_FILL_FIELDS_PROMPT = """
            You are an expert at filling character sheet fields for tabletop RPG systems.
            
            You will receive:
            1. A character concept
            2. A list of fields to fill
            3. System rules and guidance
            
            CRITICAL RULES:
            - Fill EVERY field in the list
            - Respect field types: "string" for text, "number" for integers, "resource" for numbers
            - Respect min/max constraints strictly
            - If a POINT BUDGET is specified, the values for the constrained fields MUST sum to EXACTLY the required total
            - Before responding, verify the arithmetic: add up all numeric values for budget-constrained groups
            - Distribute values wisely based on the character concept
            - Use the character concept to inform your choices
            
            RESPONSE FORMAT: valid JSON - a flat object with field paths as keys.
            Language: {language}
            """;

    private static final String FALLBACK_ITEMS_PROMPT = """
            You are an expert at creating items for tabletop RPG characters.
            Based on the character concept and available item types, create 2-4 relevant items.
            Respond ONLY with valid JSON array.
            Language: {language}
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
                                      ConceptAgent conceptAgent,
                                      FieldFillerAgent fieldFillerAgent,
                                      ItemGenerationAgent itemGenerationAgent) {
        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.8)
                        .build())
                .build();
        this.objectMapper = objectMapper;
        this.webSocketController = webSocketController;
        this.conceptAgent = conceptAgent;
        this.fieldFillerAgent = fieldFillerAgent;
        this.itemGenerationAgent = itemGenerationAgent;
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

        try {
            // Step 1: Generate core concept (20% progress)
            sendProgress(sessionId, "Generating character concept...", 20);
            Map<String, Object> coreConcept = conceptAgent.generateCoreConcept(request, language);
            log.info("Generated core concept: {}", coreConcept.get("name"));

            // Step 2: Group and fill fields (40-80% progress) via FieldFillerAgent
            sendProgress(sessionId, "Filling character attributes...", 40);
            Map<String, Object> systemData = fieldFillerAgent.fillFieldsInGroups(
                    coreConcept,
                    request,
                    language,
                    (step, progress) -> sendProgress(sessionId, step, progress)
            );
            log.info("Filled fields after constraint enforcement: {} entries", systemData.size());
            // Diagnostic: log habilidades-related keys so we can confirm they were generated
            List<String> habilidadKeys = systemData.keySet().stream()
                    .filter(k -> k.toLowerCase().contains("habilidad"))
                    .collect(Collectors.toList());
            if (!habilidadKeys.isEmpty()) {
                log.info("Habilidades keys in systemData: {}", habilidadKeys);
                habilidadKeys.forEach(k -> log.info("  {} = {}", k, systemData.get(k)));
            } else {
                log.warn("No habilidades keys found in systemData. All keys: {}", systemData.keySet());
            }

            // Step 3: Generate items (90% progress) via ItemGenerationAgent
            sendProgress(sessionId, "Generating items...", 90);
            List<CreateCharacterResponse.ItemDto> items = itemGenerationAgent.generateItems(coreConcept, request, language);
            log.info("Generated {} items", items.size());

            // Assemble final response
            CreateCharacterResponse response = assembleCharacter(
                coreConcept,
                systemData,
                items,
                request.getActorType()
            );

            // Send completion notification
            sendProgress(sessionId, "Character generated successfully!", 100);
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

            log.info("Successfully generated character: {}", response.getCharacter().getActor().getName());
            return response;

        } catch (Exception e) {
            log.error("Character generation failed", e);
            
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
     * Assemble final character response from all parts
     */
    private CreateCharacterResponse assembleCharacter(
            Map<String, Object> coreConcept,
            Map<String, Object> systemData,
            List<CreateCharacterResponse.ItemDto> items,
            String actorType) {
        
        CreateCharacterResponse response = new CreateCharacterResponse();
        response.setSuccess(true);
        
        // Build actor — extract name with fallbacks for localized AI responses
        CreateCharacterResponse.ActorDto actor = new CreateCharacterResponse.ActorDto();
        String name = extractName(coreConcept);
        actor.setName(name);
        actor.setType(actorType);
        actor.setImg("icons/svg/mystery-man.svg");
        log.info("Assembled character name: '{}'", name);
        
        // Build nested system data structure from flat field paths
        Map<String, Object> nestedSystem = buildNestedStructure(systemData, coreConcept);
        log.info("Nested system top-level keys: {}", nestedSystem.keySet());
        if (nestedSystem.containsKey("habilidades")) {
            log.info("Nested habilidades structure: {}", nestedSystem.get("habilidades"));
        }
        actor.setSystem(nestedSystem);
        
        // Build character data
        CreateCharacterResponse.CharacterDataDto characterData = new CreateCharacterResponse.CharacterDataDto();
        characterData.setActor(actor);
        characterData.setItems(items);
        
        response.setCharacter(characterData);
        response.setReasoning("Character generated using multi-step approach with focused field filling");
        
        return response;
    }

    /**
     * Build nested structure from flat field paths.
     * System-agnostic: merges ALL core concept fields into the system data
     * (not just hardcoded "concepto", "biografia", "descripcion").
     */
    private Map<String, Object> buildNestedStructure(Map<String, Object> flatData, Map<String, Object> coreConcept) {
        Map<String, Object> nested = new java.util.HashMap<>();
        
        // Dynamically merge all core concept fields (except 'name' which goes to actor.name)
        for (Map.Entry<String, Object> conceptEntry : coreConcept.entrySet()) {
            String key = conceptEntry.getKey();
            if ("name".equals(key)) continue; // Name is handled at actor level
            nested.put(key, conceptEntry.getValue());
        }
        
        // Convert flat paths to nested structure
        for (Map.Entry<String, Object> entry : flatData.entrySet()) {
            String path = entry.getKey();
            Object value = entry.getValue();
            
            // Remove "system." prefix if present
            if (path.startsWith("system.")) {
                path = path.substring(7);
            }
            
            setNestedValue(nested, path, value);
        }
        
        return nested;
    }

    /**
     * Set a value in a nested map using dot notation
     */
    private void setNestedValue(Map<String, Object> map, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = map;
        
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (!current.containsKey(part)) {
                current.put(part, new java.util.HashMap<String, Object>());
            }
            Object next = current.get(part);
            if (!(next instanceof Map)) {
                // Overwrite if not a map
                next = new java.util.HashMap<String, Object>();
                current.put(part, next);
            }
            current = (Map<String, Object>) next;
        }
        
        current.put(parts[parts.length - 1], value);
    }


    /**
     * Send progress update via WebSocket
     */
    private void sendProgress(String sessionId, String step, int progress) {
        if (sessionId != null) {
            CharacterCreationEvent event = CharacterCreationEvent.builder()
                    .requestId(sessionId)
                    .currentStep(step)
                    .progress(progress)
                    .build();
            
            WebSocketMessage message = WebSocketMessage.success(
                    WebSocketMessage.MessageType.CHARACTER_GENERATION_STARTED,
                    sessionId,
                    event
            );
            webSocketController.sendCharacterUpdate(sessionId, message);
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
                    .user(u -> u.text("{userPrompt}").param("userPrompt", userPrompt))
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

    /**
     * Extract the character name from the AI's core concept response.
     * The AI might use localized keys depending on the prompt language,
     * so we try multiple candidate keys before falling back.
     */
    private String extractName(Map<String, Object> coreConcept) {
        // Try standard keys first
        List<String> candidateKeys = List.of(
                "name", "nombre", "nom", "nome", "Name",
                "character_name", "characterName",
                "actor_name", "actorName"
        );
        for (String key : candidateKeys) {
            Object val = coreConcept.get(key);
            if (val instanceof String s && !s.isBlank()) {
                return s;
            }
        }

        // Case-insensitive search
        for (Map.Entry<String, Object> entry : coreConcept.entrySet()) {
            if (entry.getKey().toLowerCase().contains("name") || entry.getKey().toLowerCase().contains("nom")) {
                if (entry.getValue() instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        }

        // Last resort: use first non-blank string value
        for (Object val : coreConcept.values()) {
            if (val instanceof String s && !s.isBlank() && s.length() < 60) {
                log.warn("Could not find 'name' key in core concept, using first short string: '{}'", s);
                return s;
            }
        }

        log.warn("No name found in core concept: {}", coreConcept.keySet());
        return "AI Character";
    }

    /**
     * Validate character data against blueprint constraints.
     * Checks field types, min/max bounds, and required fields.
     */
    public void validateAgainstBlueprint(
            Map<String, Object> characterData,
            CharacterBlueprintDto blueprint,
            ValidateCharacterResponse response) {

        if (blueprint.getActorFields() == null) return;

        for (Object fieldObj : blueprint.getActorFields()) {
            if (!(fieldObj instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> field = (Map<String, Object>) fieldObj;

            String path = (String) field.get("path");
            String type = (String) field.get("type");
            Boolean required = (Boolean) field.get("required");

            if (path == null) continue;

            // Resolve value from character data using dot notation
            Object value = resolveNestedValue(characterData, path);

            // Check required
            if (Boolean.TRUE.equals(required) && (value == null || value.toString().isBlank())) {
                response.getErrors().add(new ValidateCharacterResponse.ValidationError(
                        path, "Required field is empty", "error"));
                continue;
            }

            if (value == null) continue;

            // Check numeric constraints
            if (("number".equals(type) || "resource".equals(type)) && value instanceof Number numVal) {
                Object minObj = field.get("min");
                Object maxObj = field.get("max");

                if (minObj instanceof Number min && numVal.doubleValue() < min.doubleValue()) {
                    response.getErrors().add(new ValidateCharacterResponse.ValidationError(
                            path,
                            String.format("Value %s is below minimum %s", numVal, min),
                            "error"));
                }

                if (maxObj instanceof Number max && numVal.doubleValue() > max.doubleValue()) {
                    response.getErrors().add(new ValidateCharacterResponse.ValidationError(
                            path,
                            String.format("Value %s exceeds maximum %s", numVal, max),
                            "error"));
                }
            }
        }
    }

    private Object resolveNestedValue(Map<String, Object> data, String path) {
        String cleanPath = path.startsWith("system.") ? path.substring(7) : path;
        String[] parts = cleanPath.split("\\.");
        Object current = data;

        for (String part : parts) {
            if (current instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) current;
                current = map.get(part);
            } else {
                return null;
            }
        }
        return current;
    }
}

