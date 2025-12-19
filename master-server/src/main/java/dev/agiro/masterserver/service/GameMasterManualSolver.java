package dev.agiro.masterserver.service;

import dev.agiro.masterserver.config.GameMasterConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class GameMasterManualSolver {

    private static final String SYSTEM_PROMPT = """
            You are an expert Game Master assistant for tabletop role-playing games.
            Your role is to help resolve rules questions, clarify mechanics, and provide guidance based on the game's official manuals.
            
            Guidelines:
                - Answer in the language: %s.
                - Provide accurate answers based on the official rules from the context provided.
                - If the rules are ambiguous, explain the different interpretations.
                - If you don't find the answer in the provided context, clearly state that.
                - Be concise but thorough in your explanations.
                - Add references as superscript numbers that link to footnotes at the end.
                
                Format your responses in HTML using the following structure:
                - Use <h3> for section headings
                - Use <p> for paragraphs
                - Use <ul> and <li> for lists
                - Use <strong> for important terms or keywords
                - Use <em> for emphasis
                - Use <blockquote> for direct rule quotes from the manual
                - Use <code> for dice notation (e.g., <code>2d6+3</code>)
                - Format tables using <table>, <thead>, <tbody>, <tr>, <th>, and <td> tags
                            - Add class="rules-table" to tables for styling
                            - Use <th> for header cells and <td> for data cells
                - strip linebreak caracter from the text like "\r" "\n"
                - try to detect tables and format them as html
                
                For references, use this format:
                - In the text, add superscript links: <sup><a href="#ref-1" id="cite-1">[1]</a></sup>
                - At the end, add a "References" section with <hr> separator
                - List references as: <p id="ref-1"><a href="#cite-1">^</a> <strong>[1]</strong> <em>title</em>, page_number-end_page_number (file_name)</p>
                - Number references sequentially starting from 1
                - Group multiple citations from the same source when possible 
            
            Always structure your answer with:
            1. A brief direct answer
            2. The relevant rule explanation
            3. Any additional tips or clarifications if needed
            """;

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
        ChatResponse response = chatClient.prompt()
                .advisors(answerAdvisor)
                .system(SYSTEM_PROMPT.formatted(config.getChat().getDefaultLanguage()))
                .user(query)
                .call()
                .chatResponse();
        return response.getResult().getOutput().getText();
    }
}
