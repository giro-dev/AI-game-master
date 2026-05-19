package dev.agiro.masterserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.CreateCharacterRequest;
import dev.agiro.masterserver.dto.SystemProfileDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Specialized "agent" responsible only for generating the core character concept.
 *
 * Keeps all LLM prompting for the concept step in one place so that
 * CharacterGenerationService can act as a thin coordinator.
 */
@Slf4j
@Service
public class ConceptAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final SystemProfileService systemProfileService;
    private final SystemAwarePromptBuilder promptBuilder;
    private final GameMasterManualSolver gameMasterManualSolver;

    // Fallback prompt used only when no System Profile is available
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

    public ConceptAgent(ChatClient.Builder chatClientBuilder,
                        ObjectMapper objectMapper,
                        SystemProfileService systemProfileService,
                        SystemAwarePromptBuilder promptBuilder,
                        GameMasterManualSolver gameMasterManualSolver) {
        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.8)
                        .build())
                .build();
        this.objectMapper = objectMapper;
        this.systemProfileService = systemProfileService;
        this.promptBuilder = promptBuilder;
        this.gameMasterManualSolver = gameMasterManualSolver;
    }

    public Map<String, Object> generateCoreConcept(CreateCharacterRequest request, String language) throws Exception {
        String systemId = request.getBlueprint().getSystemId();
        SystemProfileDto profile = resolveProfile(systemId);

        // Build a slim system context for the concept step — no need for full
        // field ranges, constraints, or reference character data here. The concept
        // step only needs the system name, summary, and identity fields.
        String slimContext;
        if (profile != null) {
            slimContext = buildSlimSystemContext(profile);
        } else {
            slimContext = getActorTypeGuidance(request.getActorType(), systemId);
        }

        String userPrompt = String.format(
                "System: %s\nActor Type: %s\n\n%s\n\nUser Request: %s\n\n" +
                        "Create a character concept. " +
                        "IMPORTANT: Your response MUST be a JSON object with a \"name\" key (the character's name as a string). " +
                        "Do NOT omit the \"name\" key. Example: {\"name\": \"Character Name\", \"concept\": \"...\", ...}",
                systemId,
                request.getActorType(),
                slimContext,
                request.getPrompt()
        );

        String systemPrompt = (profile != null)
                ? promptBuilder.buildCoreConceptPrompt(profile, language)
                : FALLBACK_CORE_CONCEPT_PROMPT.replace("{language}", language);

        String responseJson = chatClient.prompt()
                .system(systemPrompt)
                .user(u -> u.text("{userPrompt}").param("userPrompt", userPrompt))
                .call()
                .content();

        log.debug("Core concept raw response: {}", responseJson);
        Map<String, Object> concept = LLMResponseUtils.parseJsonWithRetry(
                responseJson, "{}", Map.class, chatClient, objectMapper, systemPrompt, userPrompt);
        log.info("Core concept keys: {}, name='{}'", concept.keySet(), LLMResponseUtils.extractName(concept));
        return concept;
    }

    /**
     * Build a minimal system context for the concept step — only system title, summary,
     * and creation choices. Omits field ranges, constraints, and reference data
     * to save tokens (the field filler will get the full context later).
     */
    private String buildSlimSystemContext(SystemProfileDto profile) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("=== GAME SYSTEM: ").append(profile.getSystemTitle())
                .append(" (").append(profile.getSystemId()).append(") ===\n");
        if (profile.getSystemSummary() != null) {
            ctx.append("Description: ").append(profile.getSystemSummary()).append("\n");
        }
        if (profile.getCreationChoices() != null && !profile.getCreationChoices().isEmpty()) {
            ctx.append("AVAILABLE CHOICES:\n");
            for (var entry : profile.getCreationChoices().entrySet()) {
                ctx.append("- ").append(entry.getKey()).append(": ")
                        .append(String.join(", ", entry.getValue())).append("\n");
            }
        }
        return ctx.toString();
    }

    private SystemProfileDto resolveProfile(String systemId) {
        return systemProfileService.getProfile(systemId).orElse(null);
    }

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


}
