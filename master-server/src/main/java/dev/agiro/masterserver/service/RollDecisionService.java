package dev.agiro.masterserver.service;

import dev.agiro.masterserver.dto.RollDecision;
import dev.agiro.masterserver.dto.WorldStateDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class RollDecisionService {

    private static final String USER_TEMPLATE = """
            SISTEMA: {gameSystem}
            ACCIO DEL JUGADOR: {transcription}

            CONTEXT DE L'ESCENA:
            {sceneContext}

            DECISIONS RECENTS:
            {recentDecisions}

            ESTAT DEL MON:
            {worldState}
            """;

    @Value("classpath:/prompts/roll_decision_system.txt")
    private Resource systemPrompt;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final ModelRoutingService modelRoutingService;

    public RollDecisionService(VectorStore vectorStore,
                               ChatClient.Builder chatClientBuilder,
                               ModelRoutingService modelRoutingService) {
        this.vectorStore = vectorStore;
        this.modelRoutingService = modelRoutingService;
        this.chatClient = chatClientBuilder
                .defaultOptions(modelRoutingService.optionsFor("roll-decision"))
                .build();
    }

    public RollDecision decide(String transcription,
                               String gameSystem,
                               String sceneContext,
                               String recentDecisions,
                               WorldStateDto worldState) {
        QuestionAnswerAdvisor advisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .similarityThreshold(0.4d)
                        .topK(4)
                        .filterExpression("foundry_system == '%s'".formatted(gameSystem))
                        .build())
                .build();

        String systemPromptText;
        try {
            systemPromptText = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not load roll_decision_system.txt: {}", e.getMessage());
            return RollDecision.builder().needsRoll(false).reasoning("System prompt unavailable").build();
        }

        try {
            RollDecision decision = modelRoutingService.timed("roll-decision", null, () ->
                    chatClient.prompt()
                            .system(systemPromptText)
                            .advisors(advisor)
                            .user(u -> u.text(USER_TEMPLATE)
                                    .param("gameSystem", gameSystem == null ? "unknown" : gameSystem)
                                    .param("transcription", transcription == null ? "" : transcription)
                                    .param("sceneContext", sceneContext == null ? "(sense context)" : sceneContext)
                                    .param("recentDecisions", recentDecisions == null ? "(cap)" : recentDecisions)
                                    .param("worldState", worldState == null ? "(sense world state)" : worldState.toString()))
                            .call()
                            .entity(RollDecision.class));

            if (decision == null) {
                return RollDecision.builder()
                        .needsRoll(false)
                        .reasoning("Roll decision model returned empty response")
                        .build();
            }
            return decision;
        } catch (Exception e) {
            log.warn("Roll decision failed: {}", e.getMessage());
            return RollDecision.builder()
                    .needsRoll(false)
                    .reasoning("Roll decision unavailable: " + e.getMessage())
                    .build();
        }
    }
}
