package dev.agiro.masterserver.service;

import dev.agiro.masterserver.dto.AbilityDto;
import dev.agiro.masterserver.dto.GameMasterRequest;
import dev.agiro.masterserver.dto.GameMasterResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Map;

@Service
public class GameMasterService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    @Value("classpath:/prompts/session_assistant_system.txt")
    private Resource systemPromptResource;

    private final String USER_MESSAGE_TEMPLATE = """
                TOKEN: {tokenName} (ID: {tokenId})
                
                USER PROMPT: {prompt}
                
                AVAILABLE ABILITIES:
                {abilities}
                
                WORLD STATE:
                {worldState}
                
                Analyze the user's intent and respond with the appropriate JSON.
                """;

    public GameMasterService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
            this.chatClient = chatClientBuilder.defaultOptions(ChatOptions.builder()
                    .model("gpt-4.1-mini")
                    .temperature(0.7)
                    .build())
                    .build();
        this.vectorStore = vectorStore;
    }

    public GameMasterResponse processRequest(GameMasterRequest request) {

        // Build filter scoped to the game system (and world if provided)
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op filter = b.eq("foundry_system", request.getFoundrySystem() != null ? request.getFoundrySystem() : "unknown");
        if (request.getWorldId() != null) {
            filter = b.and(filter, b.eq("world_id", request.getWorldId()));
        }

        QuestionAnswerAdvisor advisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(6)
                        .filterExpression(filter.build())
                        .build())
                .build();

        Map<String, Object> userPromptParameters = Map.of(
               "tokenName", request.getTokenName(),
               "tokenId", request.getTokenId(),
                "prompt", request.getPrompt(),
                "abilities", request.getAbilities().stream().map(AbilityDto::toString).toList(),
                "worldState", request.getWorldState().toString());

        GameMasterResponse aiResponse = chatClient.prompt()
                .system(system -> system.text(systemPromptResource)
                        .param("language", "Català"))
                .advisors(advisor)
                .user(user -> user.text(USER_MESSAGE_TEMPLATE)
                        .params(userPromptParameters))
                .call()
                .entity(GameMasterResponse.class);

        if (aiResponse == null ) {
            GameMasterResponse fallback = new GameMasterResponse();
            fallback.setNarration("I couldn't determine what action to take. Please try rephrasing your request.");
            fallback.setActions(new ArrayList<>());
            return fallback;
        }

        return aiResponse;
    }

}

