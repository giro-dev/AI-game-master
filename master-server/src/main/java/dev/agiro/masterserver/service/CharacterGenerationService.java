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

    private static final String CORE_CONCEPT_SYSTEM_PROMPT = """
            You are an expert character creator for tabletop RPG systems.
            
            Create a core concept for a character based on the user's description.
            Generate ONLY the essential identity fields like name, concepto, biografia, descripcion.
            
            Respond ONLY with valid JSON:
            {
              "name": "Character Name",
              "concepto": "Brief concept (2-3 sentences)",
              "biografia": "Background story (3-4 sentences)",
              "descripcion": "Physical/personality description (2-3 sentences)"
            }
            
            Language: {language}
            Be creative and evocative. This concept will be used to fill other fields.
            """;

    private static final String FILL_FIELDS_SYSTEM_PROMPT = """
            You are an expert at filling character sheet fields for tabletop RPG systems.
            
            You will receive:
            1. A character concept (name, biografia, etc.)
            2. A list of fields to fill
            3. System rules and guidance
            
            Your job is to fill ONLY the provided fields with appropriate values that match the character concept.
            
            CRITICAL RULES:
            - Fill EVERY field in the list
            - Respect field types: "string" for text, "number" for integers, "resource" for numbers
            - Respect min/max constraints: never exceed max or go below min
            - For numeric fields: distribute values wisely, don't max everything
            - Use the character concept to inform your choices
            
            RESPONSE FORMAT:
            Respond ONLY with valid JSON - a flat object with field paths as keys:
            {
              "system.atributos.est": 5,
              "system.atributos.tam": 7,
              "system.caracteristicas.blindaje": 2
            }
            
            Language: {language}
            """;

    private static final String GENERATE_ITEMS_SYSTEM_PROMPT = """
            You are an expert at creating items for tabletop RPG characters.
            
            Based on the character concept and available item types, create 2-4 relevant items.
            
            Respond ONLY with valid JSON array:
            [
              {
                "name": "Item Name",
                "type": "itemType",
                "system": { ...item properties... }
              }
            ]
            
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

        try {
            // Step 1: Generate core concept (20% progress)
            sendProgress(sessionId, "Generating character concept...", 20);
            Map<String, Object> coreConcept = generateCoreConcept(request, language);
            log.info("Generated core concept: {}", coreConcept.get("name"));

            // Step 2: Group and fill fields (40-80% progress)
            sendProgress(sessionId, "Filling character attributes...", 40);
            Map<String, Object> systemData = fillFieldsInGroups(coreConcept, request, language, sessionId);
            log.info("Filled {} field groups", systemData.size());

            // Step 3: Generate items (90% progress)
            sendProgress(sessionId, "Generating items...", 90);
            List<CreateCharacterResponse.ItemDto> items = generateItems(coreConcept, request, language);
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
     * Step 1: Generate core character concept
     */
    private Map<String, Object> generateCoreConcept(CreateCharacterRequest request, String language) throws Exception {
        String actorGuidance = getActorTypeGuidance(request.getActorType(), request.getBlueprint().getSystemId());

        String userPrompt = String.format(
            "System: %s\nActor Type: %s\n\n%s\n\nUser Request: %s\n\nCreate a character concept.",
            request.getBlueprint().getSystemId(),
            request.getActorType(),
            actorGuidance,
            request.getPrompt()
        );

        String responseJson = chatClient.prompt()
                .system(sp -> sp.text(CORE_CONCEPT_SYSTEM_PROMPT.replace("{language}", language)))
                .user(userPrompt)
                .call()
                .content();

        responseJson = cleanJsonResponse(responseJson);
        return objectMapper.readValue(responseJson, Map.class);
    }

    /**
     * Step 2: Fill fields in semantic groups
     */
    private Map<String, Object> fillFieldsInGroups(
            Map<String, Object> coreConcept,
            CreateCharacterRequest request,
            String language,
            String sessionId) throws Exception {

        Map<String, Object> allSystemData = new java.util.HashMap<>();

        // Get actor guidance once for all field groups
        String actorGuidance = getActorTypeGuidance(request.getActorType(), request.getBlueprint().getSystemId());

        // Group fields by semantic category
        Map<String, List<CharacterBlueprintDto.FieldDto>> fieldGroups = groupFields(request.getBlueprint());

        int groupIndex = 0;
        int totalGroups = fieldGroups.size();

        for (Map.Entry<String, List<CharacterBlueprintDto.FieldDto>> entry : fieldGroups.entrySet()) {
            String groupName = entry.getKey();
            List<CharacterBlueprintDto.FieldDto> fields = entry.getValue();

            // Update progress (40% to 80% spread across groups)
            int progress = 40 + (40 * groupIndex / totalGroups);
            sendProgress(sessionId, "Filling " + groupName + "...", progress);

            log.info("Filling field group '{}' with {} fields", groupName, fields.size());

            Map<String, Object> groupValues = fillFieldGroup(coreConcept, fields, actorGuidance, language, request);
            allSystemData.putAll(groupValues);

            groupIndex++;
        }

        return allSystemData;
    }

    /**
     * Fill a single group of fields
     */
    private Map<String, Object> fillFieldGroup(
            Map<String, Object> coreConcept,
            List<CharacterBlueprintDto.FieldDto> fields,
            String actorGuidance,
            String language,
            CreateCharacterRequest request) throws Exception {

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Character Concept:\n");
        userPrompt.append(objectMapper.writeValueAsString(coreConcept)).append("\n\n");

        userPrompt.append("System Rules:\n");
        userPrompt.append(actorGuidance).append("\n\n");

        userPrompt.append("Fields to fill:\n");
        userPrompt.append(objectMapper.writeValueAsString(fields)).append("\n\n");

        userPrompt.append("Fill ALL these fields with appropriate values based on the character concept.");

        String responseJson = chatClient.prompt()
                .system(sp -> sp.text(FILL_FIELDS_SYSTEM_PROMPT.replace("{language}", language)))
                .user(userPrompt.toString())
                .call()
                .content();

        responseJson = cleanJsonResponse(responseJson);
        log.debug("Field group response: {}", responseJson);

        return objectMapper.readValue(responseJson, Map.class);
    }

    /**
     * Step 3: Generate items based on character concept
     */
    private List<CreateCharacterResponse.ItemDto> generateItems(
            Map<String, Object> coreConcept,
            CreateCharacterRequest request,
            String language) throws Exception {

        if (request.getBlueprint().getAvailableItems() == null ||
            request.getBlueprint().getAvailableItems().isEmpty()) {
            log.info("No available items in blueprint, skipping item generation");
            return List.of();
        }

        // Get RAG context for items
        List<String> itemTypes = request.getBlueprint().getAvailableItems().stream()
                .map(item -> {
                    if (item instanceof Map) {
                        return (String) ((Map<?, ?>) item).get("type");
                    }
                    return null;
                })
                .filter(type -> type != null)
                .collect(Collectors.toList());

        String itemContext = "";
        if (!itemTypes.isEmpty()) {
            try {
                itemContext = ragService.searchItemContext(
                    itemTypes,
                    request.getBlueprint().getSystemId(),
                    2
                );
            } catch (Exception e) {
                log.warn("Failed to retrieve item context", e);
            }
        }

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Character Concept:\n");
        userPrompt.append(objectMapper.writeValueAsString(coreConcept)).append("\n\n");

        userPrompt.append("Available Item Types:\n");
        userPrompt.append(objectMapper.writeValueAsString(request.getBlueprint().getAvailableItems())).append("\n\n");

        if (!itemContext.isEmpty()) {
            userPrompt.append("Item Rules:\n");
            userPrompt.append(itemContext).append("\n\n");
        }

        userPrompt.append("Create 2-4 appropriate items for this character.");

        String responseJson = chatClient.prompt()
                .system(sp -> sp.text(GENERATE_ITEMS_SYSTEM_PROMPT.replace("{language}", language)))
                .user(userPrompt.toString())
                .call()
                .content();

        responseJson = cleanJsonResponse(responseJson);
        log.debug("Items response: {}", responseJson);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemMaps = objectMapper.readValue(responseJson, List.class);

        return itemMaps.stream()
                .map(itemMap -> objectMapper.convertValue(itemMap, CreateCharacterResponse.ItemDto.class))
                .collect(Collectors.toList());
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

        // Build actor
        CreateCharacterResponse.ActorDto actor = new CreateCharacterResponse.ActorDto();
        actor.setName((String) coreConcept.get("name"));
        actor.setType(actorType);
        actor.setImg("icons/svg/mystery-man.svg");

        // Build nested system data structure from flat field paths
        Map<String, Object> nestedSystem = buildNestedStructure(systemData, coreConcept);
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
     * Build nested structure from flat field paths
     */
    private Map<String, Object> buildNestedStructure(Map<String, Object> flatData, Map<String, Object> coreConcept) {
        Map<String, Object> nested = new java.util.HashMap<>();

        // Add core concept fields (concepto, biografia, descripcion)
        if (coreConcept.containsKey("concepto")) {
            nested.put("concepto", coreConcept.get("concepto"));
        }
        if (coreConcept.containsKey("biografia")) {
            nested.put("biografia", coreConcept.get("biografia"));
        }
        if (coreConcept.containsKey("descripcion")) {
            nested.put("descripcion", coreConcept.get("descripcion"));
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
     * Group fields by semantic category
     */
    private Map<String, List<CharacterBlueprintDto.FieldDto>> groupFields(CharacterBlueprintDto blueprint) {
        Map<String, List<CharacterBlueprintDto.FieldDto>> groups = new java.util.LinkedHashMap<>();

        List<CharacterBlueprintDto.FieldDto> coreFields = new java.util.ArrayList<>();
        List<CharacterBlueprintDto.FieldDto> attributeFields = new java.util.ArrayList<>();
        List<CharacterBlueprintDto.FieldDto> skillFields = new java.util.ArrayList<>();
        List<CharacterBlueprintDto.FieldDto> resourceFields = new java.util.ArrayList<>();
        List<CharacterBlueprintDto.FieldDto> statFields = new java.util.ArrayList<>();
        List<CharacterBlueprintDto.FieldDto> otherFields = new java.util.ArrayList<>();

        for (Object fieldObj : blueprint.getActorFields()) {
            CharacterBlueprintDto.FieldDto field = objectMapper.convertValue(fieldObj, CharacterBlueprintDto.FieldDto.class);
            String path = field.getPath().toLowerCase();

            if (path.contains("concepto") || path.contains("biografia") || path.contains("descripcion") ||
                path.contains("cita") || path.contains("extras")) {
                coreFields.add(field);
            } else if (path.contains("atributo")) {
                attributeFields.add(field);
            } else if (path.contains("habilidad") || path.contains("skill")) {
                skillFields.add(field);
            } else if (path.contains("drama") || path.contains("resistencia") || path.contains("estabilidad") ||
                       path.contains("aguante") || path.contains("resource")) {
                resourceFields.add(field);
            } else if (path.contains("defensa") || path.contains("danio") || path.contains("blindaje") ||
                       path.contains("armamento") || path.contains("caracteristica")) {
                statFields.add(field);
            } else {
                otherFields.add(field);
            }
        }

        if (!coreFields.isEmpty()) groups.put("core fields", coreFields);
        if (!attributeFields.isEmpty()) groups.put("attributes", attributeFields);
        if (!skillFields.isEmpty()) groups.put("skills", skillFields);
        if (!resourceFields.isEmpty()) groups.put("resources", resourceFields);
        if (!statFields.isEmpty()) groups.put("combat stats", statFields);
        if (!otherFields.isEmpty()) groups.put("other fields", otherFields);

        return groups;
    }

    /**
     * Get actor type guidance from RAG
     */
    private String getActorTypeGuidance(String actorType, String systemId) {
        try {
            return gameMasterManualSolver.solveDoubt(
                String.format("How do I create a %s in this game system? What are the rules and important considerations?", actorType),
                systemId
            );
        } catch (Exception e) {
            log.warn("Failed to retrieve actor type guidance", e);
            return "";
        }
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

