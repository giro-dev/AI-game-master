package dev.agiro.masterserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.CharacterBlueprintDto;
import dev.agiro.masterserver.dto.CreateCharacterRequest;
import dev.agiro.masterserver.dto.CreateCharacterResponse;
import dev.agiro.masterserver.dto.ReferenceCharacterDto;
import dev.agiro.masterserver.dto.SystemProfileDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Specialized agent for generating items for a character based on its
 * concept, system profile and RAG item context.
 */
@Slf4j
@Service
public class ItemGenerationAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final SystemProfileService systemProfileService;
    private final SystemAwarePromptBuilder promptBuilder;
    private final RAGService ragService;

    private static final String FALLBACK_ITEMS_PROMPT = """
            You are an expert at creating items for tabletop RPG characters.
            Based on the character concept and available item types, create 2-4 relevant items.
            Respond ONLY with valid JSON array.
            Language: {language}
            """;

    public ItemGenerationAgent(ChatClient.Builder chatClientBuilder,
                               ObjectMapper objectMapper,
                               SystemProfileService systemProfileService,
                               SystemAwarePromptBuilder promptBuilder,
                               RAGService ragService) {
        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.8)
                        .build())
                .build();
        this.objectMapper = objectMapper;
        this.systemProfileService = systemProfileService;
        this.promptBuilder = promptBuilder;
        this.ragService = ragService;
    }

    public List<CreateCharacterResponse.ItemDto> generateItems(
            Map<String, Object> coreConcept,
            CreateCharacterRequest request,
            String language) throws Exception {

        String systemId = request.getBlueprint().getSystemId();
        var refOpt = request.getReferenceCharacter() != null
                ? java.util.Optional.of(request.getReferenceCharacter())
                : systemProfileService.getReferenceCharacter(systemId, request.getActorType());

        boolean hasBlueprint = request.getBlueprint().getAvailableItems() != null &&
                !request.getBlueprint().getAvailableItems().isEmpty();
        boolean hasReference = refOpt.isPresent() && refOpt.get().getItems() != null &&
                !refOpt.get().getItems().isEmpty();

        if (!hasBlueprint && !hasReference) {
            log.info("No available items in blueprint and no reference character, skipping item generation");
            return List.of();
        }

        SystemProfileDto profile = resolveProfile(systemId);

        // Detect required (character-definition) item types from the reference character.
        // In systems like Hitos every character MUST have skills (habilidad), traits (rasgo),
        // etc. as embedded items — they are NOT optional equipment.
        List<String> requiredItemTypes = extractRequiredItemTypes(refOpt);
        log.info("Required CDI types from reference '{}': {}",
                refOpt.map(ReferenceCharacterDto::getLabel).orElse("none"), requiredItemTypes);

        // Equipment = blueprint item types NOT already covered by CDI types
        List<CharacterBlueprintDto.ItemTypeDto> equipmentTypes = filterEquipmentTypes(
                request.getBlueprint().getAvailableItems(), requiredItemTypes);

        // RAG context scoped to equipment types only (CDIs come from the reference)
        String itemContext = buildRagContext(equipmentTypes, systemId);

        List<CreateCharacterResponse.ItemDto> allItems = new ArrayList<>();

        // ── Pass A: mandatory character-definition items ──────────────────
        if (hasReference && !requiredItemTypes.isEmpty()) {
            log.info("Pass A — generating {} required CDI type(s)", requiredItemTypes.size());
            List<CreateCharacterResponse.ItemDto> cdiItems = generateRequiredItems(
                    coreConcept, request, language, refOpt.get(), requiredItemTypes, profile);
            allItems.addAll(cdiItems);
            log.info("Pass A produced {} CDI items", cdiItems.size());
        }

        // ── Pass B: optional equipment items ─────────────────────────────
        if (!equipmentTypes.isEmpty()) {
            log.info("Pass B — generating optional equipment for {} type(s)", equipmentTypes.size());
            List<CreateCharacterResponse.ItemDto> equipItems = generateEquipmentItems(
                    coreConcept, request, language, equipmentTypes, itemContext, profile,
                    hasReference ? refOpt.get() : null);
            allItems.addAll(equipItems);
            log.info("Pass B produced {} equipment items", equipItems.size());
        }

        // ── Fallback: no reference and no equipment filtered — legacy path ─
        if (allItems.isEmpty() && hasBlueprint) {
            log.info("Falling back to legacy single-pass item generation");
            allItems.addAll(generateLegacyItems(coreConcept, request, language, profile,
                    hasReference ? refOpt.get() : null, itemContext));
        }

        return allItems;
    }

    // ── Pass A helper ─────────────────────────────────────────────────────

    /**
     * Generate all mandatory character-definition items (CDIs).
     * The AI is given an explicit per-type count and a structural example for every
     * required type so it cannot "choose" to skip skills, traits, etc.
     */
    private List<CreateCharacterResponse.ItemDto> generateRequiredItems(
            Map<String, Object> coreConcept,
            CreateCharacterRequest request,
            String language,
            ReferenceCharacterDto ref,
            List<String> requiredTypes,
            SystemProfileDto profile) throws Exception {

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Character Concept:\n");
        userPrompt.append(objectMapper.writeValueAsString(coreConcept)).append("\n\n");

        if (profile != null) {
            userPrompt.append(promptBuilder.buildSystemContext(profile)).append("\n");
        }

        userPrompt.append(promptBuilder.buildRequiredItemTypesContext(ref, requiredTypes));

        userPrompt.append("Generate ALL required items listed above. ");
        userPrompt.append("Use the reference structures as templates: keep the same field names and types, ");
        userPrompt.append("but adapt names, descriptions, and numeric values to fit the character concept.\n");
        userPrompt.append("Respond with a SINGLE JSON array containing ALL required items.");

        String systemPrompt = (profile != null)
                ? promptBuilder.buildItemGenerationPrompt(profile, language)
                : FALLBACK_ITEMS_PROMPT.replace("{language}", language);

        String responseJson = chatClient.prompt()
                .system(systemPrompt)
                .user(u -> u.text("{userPrompt}").param("userPrompt", userPrompt.toString()))
                .call()
                .content();

        responseJson = cleanJsonResponse(responseJson);
        if (!responseJson.trim().startsWith("[")) {
            responseJson = "[" + responseJson + "]";
        }
        log.debug("CDI items response: {}", responseJson);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemMaps = objectMapper.readValue(responseJson, List.class);
        return itemMaps.stream()
                .map(m -> objectMapper.convertValue(m, CreateCharacterResponse.ItemDto.class))
                .collect(Collectors.toList());
    }

    // ── Pass B helper ─────────────────────────────────────────────────────

    /**
     * Generate optional equipment items (weapons, armour, gear) for item types
     * that are NOT character-definition items.  Failures are swallowed so that a
     * broken equipment pass never blocks the CDI items.
     */
    private List<CreateCharacterResponse.ItemDto> generateEquipmentItems(
            Map<String, Object> coreConcept,
            CreateCharacterRequest request,
            String language,
            List<CharacterBlueprintDto.ItemTypeDto> equipmentTypes,
            String ragContext,
            SystemProfileDto profile,
            ReferenceCharacterDto ref) {

        try {
            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("Character Concept:\n");
            userPrompt.append(objectMapper.writeValueAsString(coreConcept)).append("\n\n");

            if (profile != null) {
                userPrompt.append(promptBuilder.buildSystemContext(profile)).append("\n");
            }

            if (ref != null) {
                userPrompt.append(promptBuilder.buildReferenceCharacterContext(ref, "items"));
            }

            userPrompt.append("Available Equipment Types:\n");
            userPrompt.append(objectMapper.writeValueAsString(equipmentTypes)).append("\n\n");

            if (!ragContext.isEmpty()) {
                userPrompt.append("Item Rules from Manuals:\n").append(ragContext).append("\n\n");
            }

            userPrompt.append("Create 1-2 optional equipment items appropriate for this character. ");
            userPrompt.append("Respond with a JSON array.");

            String systemPrompt = (profile != null)
                    ? promptBuilder.buildItemGenerationPrompt(profile, language)
                    : FALLBACK_ITEMS_PROMPT.replace("{language}", language);

            String responseJson = chatClient.prompt()
                    .system(systemPrompt)
                    .user(u -> u.text("{userPrompt}").param("userPrompt", userPrompt.toString()))
                    .call()
                    .content();

            responseJson = cleanJsonResponse(responseJson);
            if (!responseJson.trim().startsWith("[")) {
                responseJson = "[" + responseJson + "]";
            }
            log.debug("Equipment items response: {}", responseJson);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> itemMaps = objectMapper.readValue(responseJson, List.class);
            return itemMaps.stream()
                    .map(m -> objectMapper.convertValue(m, CreateCharacterResponse.ItemDto.class))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("Optional equipment generation failed (non-fatal): {}", e.getMessage());
            return List.of();
        }
    }

    // ── Legacy fallback (no reference character) ──────────────────────────

    private List<CreateCharacterResponse.ItemDto> generateLegacyItems(
            Map<String, Object> coreConcept,
            CreateCharacterRequest request,
            String language,
            SystemProfileDto profile,
            ReferenceCharacterDto ref,
            String itemContext) throws Exception {

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Character Concept:\n");
        userPrompt.append(objectMapper.writeValueAsString(coreConcept)).append("\n\n");

        if (profile != null) {
            userPrompt.append(promptBuilder.buildSystemContext(profile)).append("\n");
        }

        if (ref != null) {
            userPrompt.append(promptBuilder.buildReferenceCharacterContext(ref, "items"));
            userPrompt.append("IMPORTANT: Your generated items MUST use the EXACT same structure (type, system fields) as the reference items above.\n");
            userPrompt.append("Clone the reference item structures but change names, descriptions, and values to fit the new character concept.\n\n");
        }

        if (request.getBlueprint().getAvailableItems() != null) {
            userPrompt.append("Available Item Types:\n");
            userPrompt.append(objectMapper.writeValueAsString(request.getBlueprint().getAvailableItems())).append("\n\n");
        }

        if (!itemContext.isEmpty()) {
            userPrompt.append("Item Rules from Manuals:\n").append(itemContext).append("\n\n");
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
        log.debug("Legacy items response: {}", responseJson);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemMaps = objectMapper.readValue(responseJson, List.class);
        return itemMaps.stream()
                .map(m -> objectMapper.convertValue(m, CreateCharacterResponse.ItemDto.class))
                .collect(Collectors.toList());
    }

    // ── Utility helpers ───────────────────────────────────────────────────

    /**
     * Extract all unique item types present in the reference character's embedded items.
     * These become the mandatory CDI types for this actor type in this system.
     */
    private List<String> extractRequiredItemTypes(Optional<ReferenceCharacterDto> refOpt) {
        if (refOpt.isEmpty() || refOpt.get().getItems() == null) return List.of();

        // Heuristic: CDI types (skills, traits, powers) usually appear MANY times on a character.
        // Equipment types often appear only once (e.g., one weapon) and should not be treated as mandatory CDI types.
        Map<String, Long> counts = refOpt.get().getItems().stream()
                .map(item -> (String) item.get("type"))
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(t -> t, LinkedHashMap::new, Collectors.counting()));

        return counts.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() >= 2)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Return only the blueprint item entries whose type is NOT in the CDI list.
     * These are the optional equipment types for Pass B.
     */
    private List<CharacterBlueprintDto.ItemTypeDto> filterEquipmentTypes(
            List<CharacterBlueprintDto.ItemTypeDto> availableItems, List<String> requiredTypes) {
        if (availableItems == null || availableItems.isEmpty()) return List.of();
        if (requiredTypes.isEmpty()) return availableItems;
        return availableItems.stream()
                .filter(item -> item.getType() != null && !requiredTypes.contains(item.getType()))
                .collect(Collectors.toList());
    }

    /**
     * Build RAG context for a list of equipment item type DTOs.
     */
    private String buildRagContext(List<CharacterBlueprintDto.ItemTypeDto> equipmentTypes, String systemId) {
        List<String> typeNames = equipmentTypes.stream()
                .map(CharacterBlueprintDto.ItemTypeDto::getType)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (typeNames.isEmpty()) return "";
        try {
            return ragService.searchItemContext(typeNames, systemId, 2);
        } catch (Exception e) {
            log.warn("Failed to retrieve RAG item context: {}", e.getMessage());
            return "";
        }
    }

    private SystemProfileDto resolveProfile(String systemId) {
        return systemProfileService.getProfile(systemId).orElse(null);
    }

    private String cleanJsonResponse(String response) {
        if (response == null) return "[]";
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
