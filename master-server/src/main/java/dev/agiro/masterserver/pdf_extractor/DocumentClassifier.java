package dev.agiro.masterserver.pdf_extractor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI-powered classifier that categorises RPG document chunks into semantic types.
 * Processes chunks in batches to minimise LLM calls.
 */
@Slf4j
@Component
public class DocumentClassifier {

    private static final int BATCH_SIZE = 8;

    @Value("classpath:/prompts/chunk_classifier_system.txt")
    private Resource classifierPrompt;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public DocumentClassifier(ChatClient.Builder builder, ObjectMapper objectMapper) {
        this.chatClient = builder
                .defaultOptions(ChatOptions.builder()
                        .model("gpt-4.1-mini")
                        .temperature(0.1)
                        .build())
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Classify a list of documents in batches, enriching each with {@code chunk_type} metadata.
     */
    public List<Document> classify(List<Document> documents) {
        List<Document> classified = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i += BATCH_SIZE) {
            List<Document> batch = documents.subList(i, Math.min(i + BATCH_SIZE, documents.size()));
            classifyBatch(batch);
            classified.addAll(batch);
            log.debug("Classified batch {}/{}", Math.min(i + BATCH_SIZE, documents.size()), documents.size());
        }
        return classified;
    }

    private void classifyBatch(List<Document> batch) {
        StringBuilder userMessage = new StringBuilder();
        userMessage.append("Classify each of the following RPG document chunks. ");
        userMessage.append("Return a JSON array with one entry per chunk, in the same order.\n\n");

        for (int i = 0; i < batch.size(); i++) {
            String text = batch.get(i).getText();
            // Limit text per chunk to avoid huge prompts
            if (text.length() > 1500) text = text.substring(0, 1500) + "…";
            userMessage.append("--- CHUNK ").append(i).append(" ---\n");
            userMessage.append(text).append("\n\n");
        }

        try {
            String response = chatClient.prompt()
                    .system(classifierPrompt)

                    .user(u -> u.text("{userMessage}").param("userMessage", userMessage.toString()))
                    .call()
                    .content();

            response = cleanJson(response);
            List<Map<String, Object>> results = objectMapper.readValue(
                    response, new TypeReference<>() {});

            for (int i = 0; i < Math.min(results.size(), batch.size()); i++) {
                Map<String, Object> classification = results.get(i);
                Document doc = batch.get(i);
                doc.getMetadata().put("chunk_type", classification.getOrDefault("category", "other"));
                if (classification.containsKey("section_title")) {
                    doc.getMetadata().put("section_title", classification.get("section_title"));
                }
                if (classification.containsKey("entity_names")) {
                    doc.getMetadata().put("entity_names", classification.get("entity_names"));
                }
            }
        } catch (Exception e) {
            log.warn("Classification batch failed, marking as 'other': {}", e.getMessage());
            for (Document doc : batch) {
                doc.getMetadata().putIfAbsent("chunk_type", "other");
            }
        }
    }

    private String cleanJson(String raw) {
        if (raw == null) return "[]";
        raw = raw.trim();
        if (raw.startsWith("```json")) raw = raw.substring(7);
        else if (raw.startsWith("```")) raw = raw.substring(3);
        if (raw.endsWith("```")) raw = raw.substring(0, raw.length() - 3);
        return raw.trim();
    }
}

