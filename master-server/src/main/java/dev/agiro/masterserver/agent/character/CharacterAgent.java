package dev.agiro.masterserver.agent.character;

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

/**
 * Character Agent — orchestrates the full character generation lifecycle.
 * Coordinates ConceptAgent, FieldFillerAgent, and ItemGenerationAgent.
 * <p>
 * This is the multi-agent approach: each sub-agent is responsible for one step,
 * and the CharacterAgent wires them together + handles WebSocket progress.
 */
@Slf4j
@Service
public class CharacterAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final WebSocketController webSocketController;
    private final ConceptAgent conceptAgent;
    private final FieldFillerAgent fieldFillerAgent;
    private final ItemGenerationAgent itemGenerationAgent;

    private static final String CHARACTER_EXPLANATION_PROMPT = """
            You are a Game Master who can read character sheets and describe them narratively.
            
            Given a character's complete data (attributes, items, etc.), provide a compelling narrative description.
            
            Language: Respond in {language}
            Style: Write as a Game Master would describe a character to players
            Length: 2-3 paragraphs
            """;

    public CharacterAgent(ChatClient.Builder chatClientBuilder,
                          ObjectMapper objectMapper,
                          WebSocketController webSocketController,
                          ConceptAgent conceptAgent,
                          FieldFillerAgent fieldFillerAgent,
                          ItemGenerationAgent itemGenerationAgent) {
        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder().model("gpt-4o-mini").temperature(0.8).build())
                .build();
        this.objectMapper = objectMapper;
        this.webSocketController = webSocketController;
        this.conceptAgent = conceptAgent;
        this.fieldFillerAgent = fieldFillerAgent;
        this.itemGenerationAgent = itemGenerationAgent;
    }

    public CreateCharacterResponse generateCharacter(CreateCharacterRequest request) {
        return generateCharacter(request, null);
    }

    public CreateCharacterResponse generateCharacter(CreateCharacterRequest request, String sessionId) {
        log.info("Generating {} character: {}", request.getActorType(), request.getPrompt());
        String language = request.getLanguage() != null ? request.getLanguage() : "en";

        try {
            // Step 1: Core concept
            sendProgress(sessionId, "Generating character concept...", 20);
            Map<String, Object> coreConcept = conceptAgent.generateCoreConcept(request, language);
            log.info("Concept: {}", coreConcept.get("name"));

            // Step 2: Fill fields
            sendProgress(sessionId, "Filling attributes...", 40);
            Map<String, Object> systemData = fieldFillerAgent.fillFieldsInGroups(
                    coreConcept, request, language,
                    (step, progress) -> sendProgress(sessionId, step, progress));
            log.info("Filled {} fields", systemData.size());

            // Step 3: Generate items
            sendProgress(sessionId, "Generating items...", 90);
            List<CreateCharacterResponse.ItemDto> items = itemGenerationAgent.generateItems(coreConcept, request, language);
            log.info("Generated {} items", items.size());

            // Assemble
            CreateCharacterResponse response = assembleCharacter(coreConcept, systemData, items, request.getActorType());

            // Notify completion
            sendProgress(sessionId, "Character generated!", 100);
            if (sessionId != null) {
                webSocketController.sendCharacterUpdate(sessionId, WebSocketMessage.success(
                        WebSocketMessage.MessageType.CHARACTER_GENERATION_COMPLETED, sessionId,
                        CharacterCreationEvent.builder()
                                .requestId(sessionId).characterData(response)
                                .characterName(response.getCharacter().getActor().getName())
                                .characterType(request.getActorType())
                                .currentStep("Done").progress(100).build()));
            }
            return response;

        } catch (Exception e) {
            log.error("Character generation failed", e);
            if (sessionId != null) {
                webSocketController.sendCharacterUpdate(sessionId, WebSocketMessage.error(
                        WebSocketMessage.MessageType.CHARACTER_GENERATION_FAILED, sessionId,
                        "Failed: " + e.getMessage()));
            }
            CreateCharacterResponse err = new CreateCharacterResponse();
            err.setSuccess(false);
            err.setReasoning("Failed: " + e.getMessage());
            return err;
        }
    }

    public ExplainCharacterResponse explainCharacter(ExplainCharacterRequest request) {
        try {
            String json = objectMapper.writeValueAsString(request.getCharacterData());
            String explanation = chatClient.prompt()
                    .system(CHARACTER_EXPLANATION_PROMPT.replace("{language}", "en"))
                    .user(u -> u.text("{p}").param("p", "System: " + request.getSystemId() + "\n\n" + json))
                    .call().content();
            ExplainCharacterResponse r = new ExplainCharacterResponse();
            r.setExplanation(explanation);
            return r;
        } catch (Exception e) {
            ExplainCharacterResponse r = new ExplainCharacterResponse();
            r.setExplanation("Failed: " + e.getMessage());
            return r;
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private CreateCharacterResponse assembleCharacter(
            Map<String, Object> coreConcept, Map<String, Object> systemData,
            List<CreateCharacterResponse.ItemDto> items, String actorType) {
        CreateCharacterResponse response = new CreateCharacterResponse();
        response.setSuccess(true);

        CreateCharacterResponse.ActorDto actor = new CreateCharacterResponse.ActorDto();
        actor.setName(extractName(coreConcept));
        actor.setType(actorType);
        actor.setImg("icons/svg/mystery-man.svg");
        actor.setSystem(buildNestedStructure(systemData, coreConcept));

        CreateCharacterResponse.CharacterDataDto data = new CreateCharacterResponse.CharacterDataDto();
        data.setActor(actor);
        data.setItems(items);
        response.setCharacter(data);
        response.setReasoning("Generated via multi-agent pipeline");
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildNestedStructure(Map<String, Object> flatData, Map<String, Object> coreConcept) {
        Map<String, Object> nested = new java.util.HashMap<>();
        for (var e : coreConcept.entrySet()) {
            if (!"name".equals(e.getKey())) nested.put(e.getKey(), e.getValue());
        }
        for (var e : flatData.entrySet()) {
            String path = e.getKey();
            if (path.startsWith("system.")) path = path.substring(7);
            setNestedValue(nested, path, e.getValue());
        }
        return nested;
    }

    @SuppressWarnings("unchecked")
    private void setNestedValue(Map<String, Object> map, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = map;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.computeIfAbsent(parts[i], k -> new java.util.HashMap<String, Object>());
            if (!(next instanceof Map)) { next = new java.util.HashMap<String, Object>(); current.put(parts[i], next); }
            current = (Map<String, Object>) next;
        }
        current.put(parts[parts.length - 1], value);
    }

    private void sendProgress(String sessionId, String step, int progress) {
        if (sessionId == null) return;
        webSocketController.sendCharacterUpdate(sessionId, WebSocketMessage.success(
                WebSocketMessage.MessageType.CHARACTER_GENERATION_STARTED, sessionId,
                CharacterCreationEvent.builder().requestId(sessionId).currentStep(step).progress(progress).build()));
    }

    private String extractName(Map<String, Object> coreConcept) {
        for (String k : List.of("name", "nombre", "nom", "nome", "Name", "character_name")) {
            Object v = coreConcept.get(k);
            if (v instanceof String s && !s.isBlank()) return s;
        }
        for (var e : coreConcept.entrySet()) {
            if (e.getKey().toLowerCase().contains("name") && e.getValue() instanceof String s && !s.isBlank()) return s;
        }
        return "AI Character";
    }
}

