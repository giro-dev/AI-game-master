package dev.agiro.masterserver.service;

import dev.agiro.masterserver.dto.CreateCharacterRequest;
import dev.agiro.masterserver.dto.SystemProfileDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.List;
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
    private final SystemProfileService systemProfileService;
    private final SystemAwarePromptBuilder promptBuilder;
    private final GameMasterManualSolver gameMasterManualSolver;
    private final RAGService ragService;

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
            Use the available tools to look up system-specific rules or guidance if needed.
            """;

    public ConceptAgent(ChatClient.Builder chatClientBuilder,
                        SystemProfileService systemProfileService,
                        SystemAwarePromptBuilder promptBuilder,
                        GameMasterManualSolver gameMasterManualSolver,
                        RAGService ragService,
                        ModelRoutingService modelRoutingService) {
        this.chatClient = chatClientBuilder
                .defaultOptions(modelRoutingService.optionsFor("concept-agent"))
                .build();
        this.systemProfileService = systemProfileService;
        this.promptBuilder = promptBuilder;
        this.gameMasterManualSolver = gameMasterManualSolver;
        this.ragService = ragService;
    }

    public Map<String, Object> generateCoreConcept(CreateCharacterRequest request, String language) throws Exception {
        String systemId = request.getBlueprint().getSystemId();
        SystemProfileDto profile = resolveProfile(systemId);

        // When a profile is available use it for deterministic context;
        // otherwise the LLM can query the manuals via the registered @Tool methods.
        String systemContext;
        if (profile != null) {
            systemContext = promptBuilder.buildSystemContext(profile);
        } else {
            systemContext = "";
        }

        // Inject reference character context if available
        String referenceContext = "";
        var refOpt = request.getReferenceCharacter() != null
                ? java.util.Optional.of(request.getReferenceCharacter())
                : systemProfileService.getReferenceCharacter(systemId, request.getActorType());
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

        String systemPrompt = (profile != null)
                ? promptBuilder.buildCoreConceptPrompt(profile, language)
                : FALLBACK_CORE_CONCEPT_PROMPT.replace("{language}", language);

        Map<String, Object> concept = chatClient.prompt()
                .system(systemPrompt)
                .user(u -> u.text("{userPrompt}").param("userPrompt", userPrompt))
                .tools(ragService, gameMasterManualSolver)
                .call()
                .entity(new ParameterizedTypeReference<>() {});

        if (concept == null) {
            concept = Map.of();
        }
        log.info("Core concept keys: {}, name='{}'", concept.keySet(), extractName(concept));
        return concept;
    }

    private SystemProfileDto resolveProfile(String systemId) {
        return systemProfileService.getProfile(systemId).orElse(null);
    }

    /**
     * Extract a reasonable name value from the concept map.
     * Used by this agent for logging and by CharacterGenerationService when assembling the response.
     */
    public String extractName(Map<String, Object> coreConcept) {
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
        // Fallback: any short non-blank string
        for (Object val : coreConcept.values()) {
            if (val instanceof String s && !s.isBlank() && s.length() < 60) {
                return s;
            }
        }
        return "AI Character";
    }
}
