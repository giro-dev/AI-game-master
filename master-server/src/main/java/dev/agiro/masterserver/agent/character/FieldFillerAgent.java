package dev.agiro.masterserver.agent.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.CreateCharacterRequest;
import dev.agiro.masterserver.dto.SystemProfileDto;
import dev.agiro.masterserver.tool.RAGService;
import dev.agiro.masterserver.tool.SystemProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * Field Filler Agent — second sub-agent in the character generation pipeline.
 * <p>
 * Takes the core concept produced by {@link ConceptAgent} and fills system-specific
 * actor fields group-by-group. Each field group (attributes, skills, resources, …)
 * is processed in a separate, targeted LLM call to keep prompts focused and reduce
 * hallucination of incorrect field paths.
 * <p>
 * Returns a flat map of {@code "system.path.to.field" → value} ready to be nested
 * by {@link dev.agiro.masterserver.agent.character.CharacterAgent}.
 */
@Slf4j
@Service
public class FieldFillerAgent {

    private static final String FIELD_FILLER_PROMPT = """
            You are filling in a tabletop RPG character sheet for the game system: %s.
            
            CHARACTER CONCEPT:
            %s
            
            FIELD GROUP TO FILL: %s (%s)
            Description: %s
            
            FIELDS (fill ALL of them with appropriate values):
            %s
            
            %s
            
            Rules:
            - Respect min/max ranges where specified
            - Use values consistent with the character concept
            - For string fields: use the language %s
            - For numeric fields: use numbers only, no units
            - For boolean fields: use true/false
            - If a field has choices, use one of the provided choices
            
            Respond ONLY with valid JSON: {"field.path": value, ...}
            Do NOT include markdown or explanation.
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final RAGService ragService;
    private final SystemProfileService systemProfileService;

    public FieldFillerAgent(ChatClient.Builder chatClientBuilder,
                            ObjectMapper objectMapper,
                            RAGService ragService,
                            SystemProfileService systemProfileService) {
        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder().model("gpt-4.1-mini").temperature(0.7).build())
                .build();
        this.objectMapper = objectMapper;
        this.ragService = ragService;
        this.systemProfileService = systemProfileService;
    }

    /**
     * Fill all field groups for the character, reporting progress for each group.
     *
     * @param coreConcept    the concept map from {@link ConceptAgent}
     * @param request        the original character creation request
     * @param language       two-letter language code
     * @param progressReport optional callback {@code (stepDescription, progressPercent)}
     * @return flat map of all filled fields (path → value)
     */
    public Map<String, Object> fillFieldsInGroups(
            Map<String, Object> coreConcept,
            CreateCharacterRequest request,
            String language,
            BiConsumer<String, Integer> progressReport) {

        Map<String, Object> allFields = new LinkedHashMap<>();

        // Retrieve field groups from system profile
        List<SystemProfileDto.FieldGroup> groups = getFieldGroups(request);

        if (groups.isEmpty()) {
            log.warn("[FieldFillerAgent] No field groups available for system {}",
                    request.getBlueprint() != null ? request.getBlueprint().getSystemId() : "unknown");
            return allFields;
        }

        // Filter out identity/biography groups (handled by ConceptAgent output)
        List<SystemProfileDto.FieldGroup> fillableGroups = groups.stream()
                .filter(g -> !"identity".equals(g.getCategory()))
                .toList();

        String systemId = request.getBlueprint() != null ? request.getBlueprint().getSystemId() : "unknown";
        String conceptJson = serializeConcept(coreConcept);

        int total = fillableGroups.size();
        for (int i = 0; i < total; i++) {
            SystemProfileDto.FieldGroup group = fillableGroups.get(i);
            int progress = 40 + (int) ((double) i / total * 45); // 40% → 85%

            if (progressReport != null) {
                progressReport.accept("Filling " + group.getName() + "...", progress);
            }

            try {
                Map<String, Object> groupFields = fillGroup(group, coreConcept, conceptJson, systemId, request, language);
                allFields.putAll(groupFields);
                log.debug("[FieldFillerAgent] Filled group '{}': {} fields", group.getName(), groupFields.size());
            } catch (Exception e) {
                log.warn("[FieldFillerAgent] Failed to fill group '{}': {}", group.getName(), e.getMessage());
            }
        }

        log.info("[FieldFillerAgent] Filled {} fields across {} groups", allFields.size(), fillableGroups.size());
        return allFields;
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private Map<String, Object> fillGroup(
            SystemProfileDto.FieldGroup group,
            Map<String, Object> coreConcept,
            String conceptJson,
            String systemId,
            CreateCharacterRequest request,
            String language) throws Exception {

        // Build field descriptions from blueprint
        String fieldDescriptions = buildFieldDescriptions(group.getFieldPaths(), request);

        // Optional RAG context for specific field categories
        String ragContext = fetchGroupContext(group, systemId, request.getWorldId(), coreConcept);
        String ragSection = ragContext.isBlank() ? "" :
                "REFERENCE RULES/EXAMPLES:\n" + truncate(ragContext, 1500);

        String prompt = FIELD_FILLER_PROMPT.formatted(
                systemId,
                truncate(conceptJson, 1500),
                group.getName(),
                group.getCategory(),
                group.getDescription() != null ? group.getDescription() : "",
                fieldDescriptions,
                ragSection,
                language
        );

        String raw = chatClient.prompt()
                .user(u -> u.text("{p}").param("p", prompt))
                .call().content();

        Map<String, Object> result = objectMapper.readValue(cleanJson(raw), new TypeReference<>() {});

        // Retain only fields that are in the expected group (prevent hallucinated paths)
        Set<String> expectedPaths = new HashSet<>(group.getFieldPaths());
        Map<String, Object> filtered = new LinkedHashMap<>();
        result.forEach((key, value) -> {
            if (expectedPaths.contains(key)) {
                filtered.put(key, value);
            } else {
                // Try with "system." prefix stripped/added for compatibility
                String normalised = key.startsWith("system.") ? key : "system." + key;
                if (expectedPaths.contains(normalised)) {
                    filtered.put(normalised, value);
                }
            }
        });
        return filtered;
    }

    private String buildFieldDescriptions(List<String> fieldPaths, CreateCharacterRequest request) {
        if (request.getBlueprint() == null) {
            return fieldPaths.stream()
                    .map(p -> "  - " + p)
                    .collect(java.util.stream.Collectors.joining("\n"));
        }

        // Build a lookup from blueprint fields
        Map<String, Object> fieldMeta = new LinkedHashMap<>();
        if (request.getBlueprint().getActorFields() != null) {
            for (Object f : request.getBlueprint().getActorFields()) {
                if (f instanceof Map<?, ?> fm) {
                    Object path = fm.get("path");
                    if (path instanceof String s) fieldMeta.put(s, fm);
                }
            }
        } else if (request.getBlueprint().getActor() != null &&
                   request.getBlueprint().getActor().getFields() != null) {
            for (var fd : request.getBlueprint().getActor().getFields()) {
                if (fd.getPath() != null) fieldMeta.put(fd.getPath(), fd);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String path : fieldPaths) {
            sb.append("  - ").append(path);
            Object meta = fieldMeta.get(path);
            if (meta instanceof Map<?, ?> fm) {
                if (fm.get("label") != null) sb.append(" [").append(fm.get("label")).append("]");
                if (fm.get("type") != null)  sb.append(" (type: ").append(fm.get("type")).append(")");
                if (fm.get("min") != null)   sb.append(" min=").append(fm.get("min"));
                if (fm.get("max") != null)   sb.append(" max=").append(fm.get("max"));
                if (fm.get("choices") != null) sb.append(" choices=").append(fm.get("choices"));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String fetchGroupContext(SystemProfileDto.FieldGroup group, String systemId, String worldId, Map<String, Object> concept) {
        try {
            String query = group.getName() + " " + concept.getOrDefault("name", "") + " " + concept.getOrDefault("concept", "");
            return switch (group.getCategory()) {
                case "attributes" -> ragService.searchCharacterCreationContextWithCompendium(query, systemId, worldId, 3);
                case "skills"     -> ragService.searchCharacterCreationContextWithCompendium("skills proficiencies " + query, systemId, worldId, 3);
                case "combat"     -> ragService.searchRulesContext("combat stats defense attack " + query, systemId, 3);
                default           -> "";
            };
        } catch (Exception e) {
            return "";
        }
    }

    private List<SystemProfileDto.FieldGroup> getFieldGroups(CreateCharacterRequest request) {
        if (request.getBlueprint() == null || request.getBlueprint().getSystemId() == null) {
            return List.of();
        }
        return systemProfileService.getProfile(request.getBlueprint().getSystemId())
                .map(SystemProfileDto::getFieldGroups)
                .filter(groups -> groups != null && !groups.isEmpty())
                .orElseGet(() -> buildFallbackGroups(request));
    }

    private List<SystemProfileDto.FieldGroup> buildFallbackGroups(CreateCharacterRequest request) {
        if (request.getBlueprint() == null) return List.of();

        List<String> allPaths = new ArrayList<>();
        if (request.getBlueprint().getActorFields() != null) {
            for (Object f : request.getBlueprint().getActorFields()) {
                if (f instanceof Map<?, ?> fm && fm.get("path") instanceof String s) allPaths.add(s);
            }
        } else if (request.getBlueprint().getActor() != null &&
                   request.getBlueprint().getActor().getFields() != null) {
            request.getBlueprint().getActor().getFields().forEach(f -> { if (f.getPath() != null) allPaths.add(f.getPath()); });
        }

        if (allPaths.isEmpty()) return List.of();

        // Single fallback group with all paths
        return List.of(SystemProfileDto.FieldGroup.builder()
                .name("Character Data")
                .description("All character fields")
                .category("other")
                .fieldPaths(allPaths)
                .build());
    }

    private String serializeConcept(Map<String, Object> concept) {
        try {
            return objectMapper.writeValueAsString(concept);
        } catch (Exception e) {
            return concept.toString();
        }
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
