package dev.agiro.masterserver.agent.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.CharacterBlueprintDto;
import dev.agiro.masterserver.dto.CreateCharacterRequest;
import dev.agiro.masterserver.dto.CreateCharacterResponse;
import dev.agiro.masterserver.tool.RAGService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Item Generation Agent — third sub-agent in the character generation pipeline.
 * <p>
 * Generates items (weapons, armor, equipment, spells, etc.) that fit the character
 * concept and the active game system. Uses the item generation system prompt and
 * optionally retrieves item examples from the RAG knowledge base.
 */
@Slf4j
@Service
public class ItemGenerationAgent {

    @Value("classpath:/prompts/item_generation_system.txt")
    private Resource itemGenerationPrompt;

    private static final int DEFAULT_ITEM_COUNT = 3;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final RAGService ragService;

    public ItemGenerationAgent(ChatClient.Builder chatClientBuilder,
                               ObjectMapper objectMapper,
                               RAGService ragService) {
        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder().model("gpt-4.1-mini").temperature(0.85).build())
                .build();
        this.objectMapper = objectMapper;
        this.ragService = ragService;
    }

    /**
     * Generate items for the character based on the concept and blueprint.
     *
     * @param coreConcept the character concept map from {@link ConceptAgent}
     * @param request     the original character creation request
     * @param language    two-letter language code
     * @return list of generated items ready to embed in the character response
     */
    public List<CreateCharacterResponse.ItemDto> generateItems(
            Map<String, Object> coreConcept,
            CreateCharacterRequest request,
            String language) {

        log.info("[ItemGenerationAgent] Generating items for character: {}", coreConcept.get("name"));

        String systemId = request.getBlueprint() != null ? request.getBlueprint().getSystemId() : null;
        List<String> validItemTypes = extractValidItemTypes(request.getBlueprint());

        if (validItemTypes.isEmpty()) {
            log.warn("[ItemGenerationAgent] No valid item types available, skipping item generation");
            return List.of();
        }

        try {
            String systemPrompt = buildSystemPrompt(language);
            String userPrompt = buildUserPrompt(coreConcept, request, validItemTypes, systemId, language);

            String raw = chatClient.prompt()
                    .system(systemPrompt)
                    .user(u -> u.text("{p}").param("p", userPrompt))
                    .call().content();

            return parseItems(raw, validItemTypes);

        } catch (Exception e) {
            log.warn("[ItemGenerationAgent] Item generation failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private String buildSystemPrompt(String language) {
        try {
            String base = itemGenerationPrompt.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            return base.replace("{language}", language);
        } catch (Exception e) {
            return "You are an expert item creator for tabletop RPGs. Respond in " + language +
                   ". Respond ONLY with valid JSON: {\"items\": [...], \"reasoning\": \"...\"}";
        }
    }

    private String buildUserPrompt(Map<String, Object> coreConcept, CreateCharacterRequest request,
                                    List<String> validItemTypes, String systemId, String language) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== CHARACTER ===\n");
        sb.append("Name: ").append(coreConcept.getOrDefault("name", "Unknown")).append("\n");
        sb.append("Concept: ").append(coreConcept.getOrDefault("concept", request.getPrompt())).append("\n");
        sb.append("Backstory: ").append(coreConcept.getOrDefault("backstory", "")).append("\n\n");

        sb.append("=== VALID ITEM TYPES (you MUST use one of these) ===\n");
        validItemTypes.forEach(t -> sb.append("  - ").append(t).append("\n"));
        sb.append("\n");

        // RAG item examples
        if (systemId != null) {
            String itemContext = ragService.searchItemContext(
                    "items equipment for " + coreConcept.getOrDefault("concept", request.getPrompt()),
                    systemId, request.getWorldId(), 4);
            if (!itemContext.isBlank()) {
                sb.append("=== SYSTEM ITEM EXAMPLES ===\n")
                  .append(truncate(itemContext, 2000)).append("\n\n");
            }
        }

        // Item blueprint fields
        if (request.getBlueprint() != null && request.getBlueprint().getAvailableItems() != null) {
            sb.append("=== ITEM FIELD SCHEMAS ===\n");
            request.getBlueprint().getAvailableItems().forEach(itemType -> {
                sb.append("Type '").append(itemType.getType()).append("'");
                if (itemType.getLabel() != null) sb.append(" (").append(itemType.getLabel()).append(")");
                if (itemType.getFields() != null && !itemType.getFields().isEmpty()) {
                    sb.append(" fields: ");
                    itemType.getFields().stream().limit(10)
                            .forEach(f -> sb.append(f.getPath()).append("(").append(f.getType()).append(") "));
                }
                sb.append("\n");
            });
            sb.append("\n");
        }

        sb.append("=== REQUEST ===\n");
        sb.append("Generate ").append(DEFAULT_ITEM_COUNT).append(" items for this character in language: ")
          .append(language).append(".\n");
        sb.append("Items should fit the character's concept and background.");

        return sb.toString();
    }

    private List<String> extractValidItemTypes(CharacterBlueprintDto blueprint) {
        if (blueprint == null) return List.of();

        if (blueprint.getAvailableItems() != null && !blueprint.getAvailableItems().isEmpty()) {
            return blueprint.getAvailableItems().stream()
                    .map(CharacterBlueprintDto.ItemTypeDto::getType)
                    .filter(t -> t != null && !t.isBlank())
                    .collect(Collectors.toList());
        }

        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<CreateCharacterResponse.ItemDto> parseItems(String raw, List<String> validItemTypes) {
        List<CreateCharacterResponse.ItemDto> items = new ArrayList<>();
        try {
            String json = cleanJson(raw);
            Map<String, Object> response = objectMapper.readValue(json, new TypeReference<>() {});

            Object itemsObj = response.get("items");
            if (!(itemsObj instanceof List<?> rawItems)) return items;

            for (Object rawItem : rawItems) {
                if (!(rawItem instanceof Map<?, ?> itemMap)) continue;

                CreateCharacterResponse.ItemDto dto = new CreateCharacterResponse.ItemDto();
                dto.setName(getString(itemMap, "name"));
                String type = getString(itemMap, "type");

                // Validate type; fall back to first valid type if invalid
                if (!validItemTypes.contains(type)) {
                    log.warn("[ItemGenerationAgent] Invalid type '{}', using '{}'", type, validItemTypes.getFirst());
                    type = validItemTypes.getFirst();
                }
                dto.setType(type);
                dto.setImg(getString(itemMap, "img") != null ? getString(itemMap, "img") : "icons/svg/item-bag.svg");

                Object systemObj = itemMap.get("system");
                if (systemObj instanceof Map<?, ?> systemMap) {
                    dto.setSystem((Map<String, Object>) systemMap);
                }

                if (dto.getName() != null && !dto.getName().isBlank()) {
                    items.add(dto);
                }
            }

        } catch (Exception e) {
            log.warn("[ItemGenerationAgent] Failed to parse items response: {}", e.getMessage());
        }
        return items;
    }

    private String getString(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String t = raw.trim();
        if (t.startsWith("```json")) t = t.substring(7);
        else if (t.startsWith("```")) t = t.substring(3);
        if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        return t.trim();
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
