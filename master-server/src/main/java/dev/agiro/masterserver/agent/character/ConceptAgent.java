package dev.agiro.masterserver.agent.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.CharacterBlueprintDto;
import dev.agiro.masterserver.dto.CreateCharacterRequest;
import dev.agiro.masterserver.dto.ReferenceCharacterDto;
import dev.agiro.masterserver.tool.RAGService;
import dev.agiro.masterserver.tool.SystemProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Concept Agent — first sub-agent in the character generation pipeline.
 * <p>
 * Generates the core narrative concept for a character: name, backstory,
 * personality, motivation, and high-level stat targets. The output is a
 * free-form {@code Map<String, Object>} that later agents use to fill
 * system-specific fields.
 * <p>
 * Context sources (in priority order):
 * <ol>
 *   <li>Reference character (structural template from a GM-approved real character)</li>
 *   <li>RAG character creation rules for the active system</li>
 *   <li>System profile constraints and creation steps</li>
 *   <li>The GM's free-text prompt</li>
 * </ol>
 */
@Slf4j
@Service
public class ConceptAgent {

    @Value("classpath:/prompts/character_generation_system.txt")
    private Resource characterGenerationPrompt;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final RAGService ragService;
    private final SystemProfileService systemProfileService;

    public ConceptAgent(ChatClient.Builder chatClientBuilder,
                        ObjectMapper objectMapper,
                        RAGService ragService,
                        SystemProfileService systemProfileService) {
        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder().model("gpt-4.1-mini").temperature(0.9).build())
                .build();
        this.objectMapper = objectMapper;
        this.ragService = ragService;
        this.systemProfileService = systemProfileService;
    }

    /**
     * Generate the core concept map for a character.
     *
     * @param request  the character creation request (prompt, actorType, systemId…)
     * @param language two-letter language code for the AI response
     * @return a flat {@code Map<String, Object>} containing at minimum {@code name},
     *         plus narrative and high-level stat fields
     */
    public Map<String, Object> generateCoreConcept(CreateCharacterRequest request, String language) {
        log.info("[ConceptAgent] Generating concept for: {}", request.getPrompt());

        String systemPrompt = buildSystemPrompt(request, language);
        String userPrompt = buildUserPrompt(request);

        try {
            String raw = chatClient.prompt()
                    .system(systemPrompt)
                    .user(u -> u.text("{p}").param("p", userPrompt))
                    .call().content();

            return objectMapper.readValue(cleanJson(raw), new TypeReference<>() {});

        } catch (Exception e) {
            log.warn("[ConceptAgent] LLM call failed, using fallback: {}", e.getMessage());
            return Map.of("name", "Unknown Character", "concept", request.getPrompt());
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private String buildSystemPrompt(CreateCharacterRequest request, String language) {
        try {
            String base = characterGenerationPrompt.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            return base.replace("{language}", language);
        } catch (Exception e) {
            return "You are an expert character creator for tabletop RPGs. Respond in " + language + ". Respond ONLY with valid JSON.";
        }
    }

    private String buildUserPrompt(CreateCharacterRequest request) {
        StringBuilder sb = new StringBuilder();

        // 1. Reference character (structural template)
        if (request.getBlueprint() != null && request.getBlueprint().getSystemId() != null) {
            systemProfileService.getReferenceCharacter(
                    request.getBlueprint().getSystemId(), request.getActorType())
                    .ifPresent(ref -> {
                        sb.append("=== REFERENCE CHARACTER (follow this structure) ===\n");
                        appendReference(sb, ref);
                        sb.append("\n\n");
                    });
        }

        // 2. System profile summary
        if (request.getBlueprint() != null && request.getBlueprint().getSystemId() != null) {
            systemProfileService.getProfile(request.getBlueprint().getSystemId())
                    .ifPresent(profile -> {
                        if (profile.getSystemSummary() != null) {
                            sb.append("=== SYSTEM ===\n").append(profile.getSystemSummary()).append("\n\n");
                        }
                        if (profile.getCharacterCreationSteps() != null && !profile.getCharacterCreationSteps().isEmpty()) {
                            sb.append("=== CHARACTER CREATION STEPS ===\n");
                            profile.getCharacterCreationSteps().forEach(step -> sb.append("- ").append(step).append("\n"));
                            sb.append("\n");
                        }
                        if (profile.getCreationChoices() != null && !profile.getCreationChoices().isEmpty()) {
                            sb.append("=== AVAILABLE CHOICES ===\n");
                            profile.getCreationChoices().forEach((category, choices) ->
                                    sb.append(category).append(": ").append(choices).append("\n"));
                            sb.append("\n");
                        }
                    });
        }

        // 3. RAG rules context
        String systemId = request.getBlueprint() != null ? request.getBlueprint().getSystemId() : null;
        if (systemId != null) {
            String rules = ragService.searchCharacterCreationContext(request.getPrompt(), systemId, request.getWorldId(), 5);
            if (!rules.isBlank()) {
                sb.append("=== RULES FROM MANUALS ===\n").append(truncate(rules, 3000)).append("\n\n");
            }
        }

        // 4. Blueprint (available fields)
        if (request.getBlueprint() != null) {
            sb.append("=== ACTOR BLUEPRINT ===\n");
            appendBlueprint(sb, request.getBlueprint());
            sb.append("\n\n");
        }

        // 5. The GM's prompt
        sb.append("=== CHARACTER REQUEST ===\n");
        sb.append("Actor type: ").append(request.getActorType()).append("\n");
        sb.append("Prompt: ").append(request.getPrompt()).append("\n\n");

        sb.append("Generate the character concept as a JSON object. ");
        sb.append("Include at minimum: name, concept/archetype, background/backstory, personality_traits, ");
        sb.append("and key stat targets that match the game system. Respond ONLY with valid JSON.");

        return sb.toString();
    }

    private void appendReference(StringBuilder sb, ReferenceCharacterDto ref) {
        try {
            sb.append("Label: ").append(ref.getLabel()).append("\n");
            if (ref.getActorData() != null) {
                sb.append("Actor structure: ").append(
                        truncate(objectMapper.writeValueAsString(ref.getActorData()), 2000)).append("\n");
            }
        } catch (Exception e) {
            sb.append("[reference serialization failed]\n");
        }
    }

    private void appendBlueprint(StringBuilder sb, CharacterBlueprintDto blueprint) {
        try {
            if (blueprint.getActorFields() != null && !blueprint.getActorFields().isEmpty()) {
                sb.append("Available fields: ")
                  .append(truncate(objectMapper.writeValueAsString(blueprint.getActorFields()), 1500));
            } else if (blueprint.getActor() != null && blueprint.getActor().getFields() != null) {
                sb.append("Available fields: ")
                  .append(truncate(objectMapper.writeValueAsString(blueprint.getActor().getFields()), 1500));
            }
            if (blueprint.getAvailableItems() != null && !blueprint.getAvailableItems().isEmpty()) {
                sb.append("\nAvailable item types: ");
                blueprint.getAvailableItems().forEach(it -> sb.append(it.getType()).append(", "));
            }
        } catch (Exception e) {
            sb.append("[blueprint serialization failed]\n");
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
