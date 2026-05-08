package dev.agiro.masterserver.service;

import dev.agiro.masterserver.dto.RollDecision;
import dev.agiro.masterserver.dto.WorldStateDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

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

    @Tool(description = "Decide whether a player's action requires a dice roll. Returns a RollDecision describing the roll type, skill, ability, and difficulty if a roll is needed.")
    public RollDecision decide(String transcription,
                               String gameSystem,
                               String sceneContext,
                               String recentDecisions,
                               WorldStateDto worldState) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        SearchRequest.Builder searchBuilder = SearchRequest.builder()
                .similarityThreshold(0.4d)
                .topK(4);
        if (gameSystem != null && !gameSystem.isBlank()) {
            searchBuilder.filterExpression(b.eq("foundry_system", gameSystem).build());
        }

        QuestionAnswerAdvisor advisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchBuilder.build())
                .build();

        try {
            RollDecision decision = modelRoutingService.timed("roll-decision", null, () ->
                    chatClient.prompt()
                            .system(s -> s.text(systemPrompt))
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
