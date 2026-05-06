package dev.agiro.masterserver.service;

import dev.agiro.masterserver.config.GameMasterConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.annotation.Tool;
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
    private final ChatMemory chatMemory;

    public GameMasterManualSolver(VectorStore vectorStore,
                                  ChatClient.Builder builder,
                                  GameMasterConfig config,
                                  ChatMemory chatMemory,
                                  ModelRoutingService modelRoutingService) {
        this.vectorStore = vectorStore;
        this.chatClient = builder
                .defaultOptions(modelRoutingService.optionsFor("manual-answer"))
                .build();
        this.config = config;
        this.chatMemory = chatMemory;
    }

    public String solveDoubt(String query, String gameSystem) {
        return solveDoubt(query, gameSystem, null);
    }

    @Tool(description = "Answer a tabletop RPG rules question by searching the uploaded game manuals. Use this when you need game-system-specific rules or guidance.")
    public String solveDoubt(String query, String gameSystem, String conversationId) {
        QuestionAnswerAdvisor answerAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .similarityThreshold(0.4d)
                        .topK(6)
                        .filterExpression("foundry_system == '%s'".formatted(gameSystem))
                        .build())
                .build();

        String convId = conversationId != null ? conversationId : "manual-" + gameSystem;
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(convId)
                .build();

        String result = chatClient.prompt()
                .advisors(answerAdvisor, memoryAdvisor)
                .system(s -> s.text(systemResource).param("language", config.getChat().getDefaultLanguage()))
                .user(u -> u.text("{query}").param("query", query))
                .call()
                .content();

        if (result == null || result.isBlank()) {
            return "";
        }
        return result;
    }
}
