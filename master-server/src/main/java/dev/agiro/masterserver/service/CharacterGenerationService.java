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
    private final RAGService ragService;
    private final GameMasterManualSolver gameMasterManualSolver;
    private final SystemProfileService systemProfileService;
    private final SystemAwarePromptBuilder promptBuilder;

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
                                      RAGService ragService,
                                      GameMasterManualSolver gameMasterManualSolver,
                                      SystemProfileService systemProfileService,
                                      SystemAwarePromptBuilder promptBuilder) {
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
        this.systemProfileService = systemProfileService;
        this.promptBuilder = promptBuilder;
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

            // Step 2.5: Enforce constraints (programmatic correction)
            sendProgress(sessionId, "Verifying constraints...", 85);
            systemData = enforceConstraints(systemData, request);
            log.info("Constraints enforced on {} fields", systemData.size());

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
     * Try to resolve the System Knowledge Profile for the current request.
     */
    private SystemProfileDto resolveProfile(String systemId) {
        return systemProfileService.getProfile(systemId).orElse(null);
    }

    /**
     * Step 1: Generate core character concept
     * Uses the System Profile for dynamic prompts when available.
     */
    private Map<String, Object> generateCoreConcept(CreateCharacterRequest request, String language) throws Exception {
        String systemId = request.getBlueprint().getSystemId();
        SystemProfileDto profile = resolveProfile(systemId);

        // Build system context: profile-aware or fallback to RAG guidance
        String systemContext;
        if (profile != null) {
            systemContext = promptBuilder.buildSystemContext(profile);
        } else {
            systemContext = getActorTypeGuidance(request.getActorType(), systemId);
        }

        // Inject reference character context if available
        String referenceContext = "";
        var refOpt = systemProfileService.getReferenceCharacter(systemId, request.getActorType());
        if (refOpt.isPresent()) {
            referenceContext = promptBuilder.buildReferenceCharacterContext(refOpt.get(), "system");
            log.info("Using reference character '{}' for core concept generation", refOpt.get().getLabel());
        }
        
        String userPrompt = String.format(
            "System: %s\nActor Type: %s\n\n%s\n\n%s\n\nUser Request: %s\n\n" +
            "Create a character concept. " +
            "IMPORTANT: Your response MUST be a JSON object with a \"name\" key (the character's name as a string). " +
            "Do NOT omit the \"name\" key. Example: {\"name\": \"Character Name\", \"concept\": \"...\", ...}",
            systemId,
            request.getActorType(),
            systemContext,
            referenceContext,
            request.getPrompt()
        );

        // Use profile-aware prompt or fallback
        String systemPrompt = (profile != null)
                ? promptBuilder.buildCoreConceptPrompt(profile, language)
                : FALLBACK_CORE_CONCEPT_PROMPT.replace("{language}", language);

        String responseJson = chatClient.prompt()
                .system(systemPrompt)
                .user(u -> u.text("{userPrompt}").param("userPrompt", userPrompt))
                .call()
                .content();

        responseJson = cleanJsonResponse(responseJson);
        log.debug("Core concept raw response: {}", responseJson);
        Map<String, Object> concept = objectMapper.readValue(responseJson, Map.class);
        log.info("Core concept keys: {}, name='{}'", concept.keySet(), concept.get("name"));
        return concept;
    }

    /**
     * Step 2: Fill fields in semantic groups.
     * Uses the System Profile for dynamic grouping when available,
     * falls back to the legacy hardcoded grouping otherwise.
     */
    private Map<String, Object> fillFieldsInGroups(
            Map<String, Object> coreConcept, 
            CreateCharacterRequest request, 
            String language,
            String sessionId) throws Exception {
        
        Map<String, Object> allSystemData = new java.util.HashMap<>();
        String systemId = request.getBlueprint().getSystemId();
        SystemProfileDto profile = resolveProfile(systemId);

        // Build system context once
        String systemContext;
        if (profile != null) {
            systemContext = promptBuilder.buildSystemContext(profile);
        } else {
            systemContext = getActorTypeGuidance(request.getActorType(), systemId);
        }
        
        // Group fields: profile-based dynamic grouping or legacy fallback
        Map<String, List<CharacterBlueprintDto.FieldDto>> fieldGroups;
        if (profile != null && profile.getFieldGroups() != null && !profile.getFieldGroups().isEmpty()) {
            fieldGroups = groupFieldsFromProfile(profile, request.getBlueprint());
            log.info("Using profile-based field grouping: {} groups", fieldGroups.size());
        } else {
            fieldGroups = groupFieldsLegacy(request.getBlueprint());
            log.info("Using legacy field grouping: {} groups", fieldGroups.size());
        }
        
        int groupIndex = 0;
        int totalGroups = fieldGroups.size();
        
        for (Map.Entry<String, List<CharacterBlueprintDto.FieldDto>> entry : fieldGroups.entrySet()) {
            String groupName = entry.getKey();
            List<CharacterBlueprintDto.FieldDto> fields = entry.getValue();
            
            int progress = 40 + (40 * groupIndex / Math.max(totalGroups, 1));
            sendProgress(sessionId, "Filling " + groupName + "...", progress);
            
            log.info("Filling field group '{}' with {} fields", groupName, fields.size());
            
            Map<String, Object> groupValues = fillFieldGroup(coreConcept, fields, systemContext, language, request);
            allSystemData.putAll(groupValues);
            
            groupIndex++;
        }
        
        return allSystemData;
    }

    /**
     * Step 2.5: Programmatically enforce constraints on AI-generated values.
     * This catches cases where the AI doesn't respect point budgets exactly.
     * Applies proportional scaling to ensure budget constraints are met.
     */
    private Map<String, Object> enforceConstraints(Map<String, Object> systemData, CreateCharacterRequest request) {
        String systemId = request.getBlueprint().getSystemId();
        SystemProfileDto profile = resolveProfile(systemId);
        
        if (profile == null || profile.getDetectedConstraints() == null) {
            return systemData;
        }
        
        Map<String, Object> corrected = new java.util.HashMap<>(systemData);
        
        for (var constraint : profile.getDetectedConstraints()) {
            if (!"point_budget".equals(constraint.getType())) continue;
            if (constraint.getParameters() == null) continue;
            
            Number totalBudgetNum = (Number) constraint.getParameters().get("total");
            if (totalBudgetNum == null) continue;
            
            double totalBudget = totalBudgetNum.doubleValue();
            String budgetPath = constraint.getFieldPath();
            Number minValNum = (Number) constraint.getParameters().get("min");
            Number maxValNum = (Number) constraint.getParameters().get("max");
            double minVal = minValNum != null ? minValNum.doubleValue() : 0;
            double maxVal = maxValNum != null ? maxValNum.doubleValue() : Double.MAX_VALUE;
            
            // Collect all numeric fields under this budget path
            List<String> budgetFieldKeys = corrected.keySet().stream()
                    .filter(k -> k.startsWith(budgetPath))
                    .filter(k -> {
                        Object v = corrected.get(k);
                        return v instanceof Number;
                    })
                    .collect(Collectors.toList());
            
            if (budgetFieldKeys.isEmpty()) continue;
            
            // Calculate current total
            double currentTotal = budgetFieldKeys.stream()
                    .mapToDouble(k -> ((Number) corrected.get(k)).doubleValue())
                    .sum();
            
            if (Math.abs(currentTotal - totalBudget) < 0.01) {
                log.info("Budget constraint '{}' already satisfied: total={}", budgetPath, currentTotal);
                continue;
            }
            
            log.info("Enforcing budget constraint '{}': AI total={}, required={}, fields={}",
                    budgetPath, currentTotal, totalBudget, budgetFieldKeys.size());
            
            // Strategy: proportional scaling with clamping to min/max
            if (currentTotal > 0) {
                double scale = totalBudget / currentTotal;
                double runningTotal = 0;
                
                for (int i = 0; i < budgetFieldKeys.size(); i++) {
                    String key = budgetFieldKeys.get(i);
                    double original = ((Number) corrected.get(key)).doubleValue();
                    
                    if (i == budgetFieldKeys.size() - 1) {
                        // Last field: assign remaining budget to avoid rounding errors
                        double remaining = totalBudget - runningTotal;
                        double clamped = Math.max(minVal, Math.min(maxVal, remaining));
                        corrected.put(key, (int) Math.round(clamped));
                    } else {
                        double scaled = original * scale;
                        double clamped = Math.max(minVal, Math.min(maxVal, scaled));
                        int rounded = (int) Math.round(clamped);
                        corrected.put(key, rounded);
                        runningTotal += rounded;
                    }
                }
                
                // Verify and do a second pass if rounding caused drift
                double finalTotal = budgetFieldKeys.stream()
                        .mapToDouble(k -> ((Number) corrected.get(k)).doubleValue())
                        .sum();
                
                if (Math.abs(finalTotal - totalBudget) > 0.01) {
                    // Distribute remaining difference across fields with room
                    int diff = (int) Math.round(totalBudget - finalTotal);
                    for (String key : budgetFieldKeys) {
                        if (diff == 0) break;
                        int current = ((Number) corrected.get(key)).intValue();
                        if (diff > 0 && current < maxVal) {
                            int add = (int) Math.min(diff, maxVal - current);
                            corrected.put(key, current + add);
                            diff -= add;
                        } else if (diff < 0 && current > minVal) {
                            int sub = (int) Math.min(-diff, current - minVal);
                            corrected.put(key, current - sub);
                            diff += sub;
                        }
                    }
                }
                
                double verifiedTotal = budgetFieldKeys.stream()
                        .mapToDouble(k -> ((Number) corrected.get(k)).doubleValue())
                        .sum();
                log.info("Budget constraint '{}' enforced: {} → {} (target={})",
                        budgetPath, currentTotal, verifiedTotal, totalBudget);
            } else {
                // All zeros or negatives: distribute budget equally
                int perField = (int) Math.round(totalBudget / budgetFieldKeys.size());
                double runningTotal = 0;
                for (int i = 0; i < budgetFieldKeys.size(); i++) {
                    if (i == budgetFieldKeys.size() - 1) {
                        int remaining = (int) Math.round(totalBudget - runningTotal);
                        corrected.put(budgetFieldKeys.get(i), Math.max((int) minVal, Math.min((int) maxVal, remaining)));
                    } else {
                        int clamped = Math.max((int) minVal, Math.min((int) maxVal, perField));
                        corrected.put(budgetFieldKeys.get(i), clamped);
                        runningTotal += clamped;
                    }
                }
                log.info("Budget constraint '{}' enforced from all-zero: distributed {} equally", budgetPath, totalBudget);
            }
        }
        
        // Enforce range constraints
        for (var constraint : profile.getDetectedConstraints()) {
            if (!"range".equals(constraint.getType())) continue;
            if (constraint.getParameters() == null) continue;
            
            String path = constraint.getFieldPath();
            Object value = corrected.get(path);
            if (!(value instanceof Number)) continue;
            
            double numVal = ((Number) value).doubleValue();
            Number minNum = (Number) constraint.getParameters().get("min");
            Number maxNum = (Number) constraint.getParameters().get("max");
            
            double min = minNum != null ? minNum.doubleValue() : Double.MIN_VALUE;
            double max = maxNum != null ? maxNum.doubleValue() : Double.MAX_VALUE;
            
            if (numVal < min || numVal > max) {
                int clamped = (int) Math.round(Math.max(min, Math.min(max, numVal)));
                corrected.put(path, clamped);
                log.info("Range constraint enforced for '{}': {} → {} (min={}, max={})", path, numVal, clamped, min, max);
            }
        }
        
        return corrected;
    }

    /**
     * Fill a single group of fields.
     * Uses profile-aware prompts when available.
     * Injects explicit budget constraints for the fields in this group.
     */
    private Map<String, Object> fillFieldGroup(
            Map<String, Object> coreConcept,
            List<CharacterBlueprintDto.FieldDto> fields,
            String systemContext,
            String language,
            CreateCharacterRequest request) throws Exception {
        
        SystemProfileDto profile = resolveProfile(request.getBlueprint().getSystemId());

        // Look up reference character for this system+actorType
        var refOpt = systemProfileService.getReferenceCharacter(
                request.getBlueprint().getSystemId(), request.getActorType());

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Character Concept:\n");
        userPrompt.append(objectMapper.writeValueAsString(coreConcept)).append("\n\n");
        
        userPrompt.append("System Rules & Context:\n");
        userPrompt.append(systemContext).append("\n\n");

        // Inject reference character's system data so the AI sees exact field paths & value types
        if (refOpt.isPresent()) {
            userPrompt.append(promptBuilder.buildReferenceCharacterContext(refOpt.get(), "system"));
        }
        
        userPrompt.append("Fields to fill:\n");
        userPrompt.append(objectMapper.writeValueAsString(fields)).append("\n\n");
        
        // Add value range hints from profile
        if (profile != null && profile.getValueRanges() != null && !profile.getValueRanges().isEmpty()) {
            userPrompt.append("Typical value ranges for reference:\n");
            for (CharacterBlueprintDto.FieldDto field : fields) {
                SystemProfileDto.ValueRange range = profile.getValueRanges().get(field.getPath());
                if (range != null) {
                    userPrompt.append("  ").append(field.getPath())
                            .append(": min=").append(range.getMin())
                            .append(", max=").append(range.getMax())
                            .append(", typical=").append(range.getTypical()).append("\n");
                }
            }
            userPrompt.append("\n");
        }
        
        // Inject explicit budget constraints for fields in THIS group
        if (profile != null && profile.getDetectedConstraints() != null) {
            for (var constraint : profile.getDetectedConstraints()) {
                if (!"point_budget".equals(constraint.getType())) continue;
                
                String budgetPath = constraint.getFieldPath();
                // Find which fields in this group fall under this budget
                List<CharacterBlueprintDto.FieldDto> budgetFields = fields.stream()
                        .filter(f -> f.getPath() != null && f.getPath().startsWith(budgetPath))
                        .filter(f -> "number".equals(f.getType()) || "resource".equals(f.getType()))
                        .collect(Collectors.toList());
                
                if (!budgetFields.isEmpty() && constraint.getParameters() != null) {
                    Number totalBudget = (Number) constraint.getParameters().get("total");
                    Number minVal = (Number) constraint.getParameters().get("min");
                    Number maxVal = (Number) constraint.getParameters().get("max");
                    
                    if (totalBudget != null) {
                        userPrompt.append("\n=== MANDATORY BUDGET FOR THIS GROUP ===\n");
                        userPrompt.append("The following ").append(budgetFields.size())
                                .append(" fields must have their values SUM to EXACTLY ")
                                .append(totalBudget).append(":\n");
                        for (var bf : budgetFields) {
                            userPrompt.append("  - ").append(bf.getPath()).append("\n");
                        }
                        if (minVal != null) {
                            userPrompt.append("Each value must be >= ").append(minVal).append("\n");
                        }
                        if (maxVal != null) {
                            userPrompt.append("Each value must be <= ").append(maxVal).append("\n");
                        }
                        userPrompt.append("VERIFY: After choosing values, confirm the sum = ")
                                .append(totalBudget).append(" before responding.\n");
                        userPrompt.append("=== END BUDGET ===\n\n");
                    }
                }
            }
        }
        
        userPrompt.append("Fill ALL these fields with appropriate values based on the character concept.");

        String systemPrompt = (profile != null)
                ? promptBuilder.buildFillFieldsPrompt(profile, language)
                : FALLBACK_FILL_FIELDS_PROMPT.replace("{language}", language);

        String responseJson = chatClient.prompt()
                .system(systemPrompt)
                .user(u -> u.text("{userPrompt}").param("userPrompt", userPrompt.toString()))
                .call()
                .content();

        responseJson = cleanJsonResponse(responseJson);
        log.debug("Field group response: {}", responseJson);
        
        return objectMapper.readValue(responseJson, Map.class);
    }

    /**
     * Step 3: Generate items based on character concept.
     * Uses profile-aware prompts when available.
     */
    private List<CreateCharacterResponse.ItemDto> generateItems(
            Map<String, Object> coreConcept,
            CreateCharacterRequest request,
            String language) throws Exception {

        String systemId = request.getBlueprint().getSystemId();
        var refOpt = systemProfileService.getReferenceCharacter(systemId, request.getActorType());

        // If no available items in blueprint AND no reference character, skip
        boolean hasBlueprint = request.getBlueprint().getAvailableItems() != null &&
                !request.getBlueprint().getAvailableItems().isEmpty();
        boolean hasReference = refOpt.isPresent() && refOpt.get().getItems() != null &&
                !refOpt.get().getItems().isEmpty();

        if (!hasBlueprint && !hasReference) {
            log.info("No available items in blueprint and no reference character, skipping item generation");
            return List.of();
        }

        SystemProfileDto profile = resolveProfile(systemId);

        // Get RAG context for items
        List<String> itemTypes = request.getBlueprint().getAvailableItems() != null
                ? request.getBlueprint().getAvailableItems().stream()
                    .map(item -> {
                        if (item instanceof Map) {
                            return (String) ((Map<?, ?>) item).get("type");
                        }
                        return null;
                    })
                    .filter(type -> type != null)
                    .collect(Collectors.toList())
                : List.of();

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
        
        // Add system context from profile
        if (profile != null) {
            userPrompt.append(promptBuilder.buildSystemContext(profile)).append("\n");
        }

        // ── Reference character's items: the single most important context for items ──
        if (hasReference) {
            userPrompt.append(promptBuilder.buildReferenceCharacterContext(refOpt.get(), "items"));
            userPrompt.append("IMPORTANT: Your generated items MUST use the EXACT same structure (type, system fields) as the reference items above.\n");
            userPrompt.append("Clone the reference item structures but change names, descriptions, and values to fit the new character concept.\n\n");
        }
        
        if (hasBlueprint) {
            userPrompt.append("Available Item Types:\n");
            userPrompt.append(objectMapper.writeValueAsString(request.getBlueprint().getAvailableItems())).append("\n\n");
        }
        
        if (!itemContext.isEmpty()) {
            userPrompt.append("Item Rules from Manuals:\n");
            userPrompt.append(itemContext).append("\n\n");
        }
        
        userPrompt.append("Create 2-4 appropriate items for this character.");

        String systemPrompt = (profile != null)
                ? promptBuilder.buildItemGenerationPrompt(profile, language)
                : FALLBACK_ITEMS_PROMPT.replace("{language}", language);

        String responseJson = chatClient.prompt()
                .system(systemPrompt)
                .user(u -> u.text("{userPrompt}").param("userPrompt", userPrompt.toString()))
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
        
        // Build actor — extract name with fallbacks for localized AI responses
        CreateCharacterResponse.ActorDto actor = new CreateCharacterResponse.ActorDto();
        String name = extractName(coreConcept);
        actor.setName(name);
        actor.setType(actorType);
        actor.setImg("icons/svg/mystery-man.svg");
        log.info("Assembled character name: '{}'", name);
        
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
     * Group fields using the System Knowledge Profile.
     * Maps profile field groups to actual blueprint fields.
     */
    private Map<String, List<CharacterBlueprintDto.FieldDto>> groupFieldsFromProfile(
            SystemProfileDto profile, CharacterBlueprintDto blueprint) {
        
        Map<String, List<String>> profileGroups = promptBuilder.getFieldGroupsFromProfile(profile);
        Map<String, List<CharacterBlueprintDto.FieldDto>> result = new java.util.LinkedHashMap<>();
        Set<String> assignedPaths = new java.util.HashSet<>();
        
        // Convert blueprint fields to a map for quick lookup
        Map<String, CharacterBlueprintDto.FieldDto> fieldMap = new java.util.LinkedHashMap<>();
        for (Object fieldObj : blueprint.getActorFields()) {
            CharacterBlueprintDto.FieldDto field = objectMapper.convertValue(fieldObj, CharacterBlueprintDto.FieldDto.class);
            fieldMap.put(field.getPath(), field);
        }
        
        // Assign fields to profile groups
        for (Map.Entry<String, List<String>> groupEntry : profileGroups.entrySet()) {
            String groupName = groupEntry.getKey();
            List<CharacterBlueprintDto.FieldDto> groupFields = new java.util.ArrayList<>();
            
            for (String profilePath : groupEntry.getValue()) {
                // Exact match
                if (fieldMap.containsKey(profilePath) && !assignedPaths.contains(profilePath)) {
                    groupFields.add(fieldMap.get(profilePath));
                    assignedPaths.add(profilePath);
                    continue;
                }
                // Prefix match (profile says "system.abilities" matches "system.abilities.str.value")
                for (Map.Entry<String, CharacterBlueprintDto.FieldDto> fe : fieldMap.entrySet()) {
                    if (fe.getKey().startsWith(profilePath) && !assignedPaths.contains(fe.getKey())) {
                        groupFields.add(fe.getValue());
                        assignedPaths.add(fe.getKey());
                    }
                }
            }
            
            if (!groupFields.isEmpty()) {
                result.put(groupName, groupFields);
            }
        }
        
        // Put any unassigned fields in an "other" group
        List<CharacterBlueprintDto.FieldDto> unassigned = new java.util.ArrayList<>();
        for (Map.Entry<String, CharacterBlueprintDto.FieldDto> fe : fieldMap.entrySet()) {
            if (!assignedPaths.contains(fe.getKey())) {
                unassigned.add(fe.getValue());
            }
        }
        if (!unassigned.isEmpty()) {
            result.put("other fields", unassigned);
        }
        
        return result;
    }

    /**
     * Legacy field grouping using path-based structural heuristic.
     * Used as fallback when no System Profile is available.
     */
    private Map<String, List<CharacterBlueprintDto.FieldDto>> groupFieldsLegacy(CharacterBlueprintDto blueprint) {
        // Structural grouping based on path hierarchy (system-agnostic)
        Map<String, List<CharacterBlueprintDto.FieldDto>> groups = new java.util.LinkedHashMap<>();
        
        for (Object fieldObj : blueprint.getActorFields()) {
            CharacterBlueprintDto.FieldDto field = objectMapper.convertValue(fieldObj, CharacterBlueprintDto.FieldDto.class);
            String path = field.getPath();
            
            // Extract the top-level group from path: "system.abilities.str" → "abilities"
            String[] parts = path.split("\\.");
            String groupKey;
            if (parts.length >= 3 && "system".equals(parts[0])) {
                groupKey = parts[1];
            } else if (parts.length >= 2) {
                groupKey = parts[0];
            } else {
                groupKey = "other";
            }
            
            // Prettify group name
            String groupName = groupKey.substring(0, 1).toUpperCase() + groupKey.substring(1)
                    .replaceAll("([A-Z])", " $1").replaceAll("_", " ").trim();
            
            groups.computeIfAbsent(groupName, k -> new java.util.ArrayList<>()).add(field);
        }
        
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
}

