package dev.agiro.masterserver.service;

import dev.agiro.masterserver.config.GameMasterConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class GameMasterManualSolver {

    @Value("classpath:/prompts/manual_system.txt")
    private Resource systemResource;
    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final GameMasterConfig config;

    public GameMasterManualSolver(VectorStore vectorStore, ChatClient.Builder builder, GameMasterConfig config) {
        this.vectorStore = vectorStore;
        this.chatClient = builder.defaultOptions(ChatOptions.builder()
                        .model(config.getChat().getDefaultModel())
                        .temperature(0.7)
                        .build()).build();
        this.config = config;
    }

    public String solveDoubt(String query, String gameSystem){
        QuestionAnswerAdvisor answerAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .similarityThreshold(0.4d)
                        .topK(6)
                        .filterExpression("game_system == '%s'".formatted(gameSystem))
                        .build())

                .build();
        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(systemResource);
        systemPromptTemplate.add("language",config.getChat().getDefaultLanguage());
        ChatResponse response = chatClient.prompt()
                .advisors(answerAdvisor)
                .system(systemPromptTemplate.render())
                .user(query)
                .call()
                .chatResponse();
        assert response != null;
        return response.getResult().getOutput().getText();
    }
}
