package dev.agiro.masterserver.pdf_extractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * For chunks classified as entity-bearing (character_creation, item_definition, npc_stat_block,
 * spell_definition), calls the LLM to extract structured JSON entities and returns them as
 * additional {@link Document}s with {@code document_type=extracted_entity}.
 */
@Slf4j
@Component
public class EntityExtractor {

    private static final Set<String> ENTITY_CHUNK_TYPES = Set.of(
            "character_creation", "item_definition", "npc_stat_block",
            "spell_definition", "bestiary", "example"
    );

    @Value("classpath:/prompts/entity_extractor_system.txt")
    private Resource extractorPrompt;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public EntityExtractor(ChatClient.Builder builder, ObjectMapper objectMapper) {
        this.chatClient = builder
                .defaultOptions(ChatOptions.builder()
                        .model("gpt-4.1-mini")
                        .temperature(0.1)
                        .build())
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Process classified documents and extract structured entities from relevant chunks.
     *
     * @return additional entity documents to store alongside the raw chunks
     */
    public List<Document> extractEntities(List<Document> classifiedDocuments) {
        List<Document> entityDocuments = new ArrayList<>();

        for (Document doc : classifiedDocuments) {
            String chunkType = (String) doc.getMetadata().getOrDefault("chunk_type", "other");
            if (!ENTITY_CHUNK_TYPES.contains(chunkType)) continue;

            try {
                List<Document> extracted = extractFromChunk(doc, chunkType);
                entityDocuments.addAll(extracted);
            } catch (Exception e) {
                log.warn("Entity extraction failed for chunk {}: {}", doc.getId(), e.getMessage());
            }
        }

        log.info("Extracted {} entity documents from {} classified chunks", entityDocuments.size(), classifiedDocuments.size());
        return entityDocuments;
    }

    private List<Document> extractFromChunk(Document sourceDoc, String chunkType) throws Exception {
        String text = sourceDoc.getText();
        if (text.length() > 3000) text = text.substring(0, 3000) + "…";

        String userMessage = String.format(
                "Chunk type: %s\n\n%s\n\nExtract all structured entities from this chunk.",
                chunkType, text
        );

        String response = chatClient.prompt()
                .system(extractorPrompt)
                .user(u -> u.text("{userMessage}").param("userMessage", userMessage))
                .call()
                .content();

        response = cleanJson(response);

        List<Map<String, Object>> entities;
        try {
            // Try parsing as array first
            entities = objectMapper.readValue(response,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            // Maybe a single object
            Map<String, Object> single = objectMapper.readValue(response, Map.class);
            entities = List.of(single);
        }

        List<Document> results = new ArrayList<>();
        for (Map<String, Object> entity : entities) {
            Map<String, Object> metadata = new HashMap<>(sourceDoc.getMetadata());
            metadata.put("document_type", "extracted_entity");
            metadata.put("entity_type", entity.getOrDefault("entity_type", chunkType));
            metadata.put("entity_name", entity.getOrDefault("name", "unknown"));
            metadata.put("source_chunk_id", sourceDoc.getId());

            String entityContent = objectMapper.writeValueAsString(entity);

            Document entityDoc = Document.builder()
                    .text(entityContent)
                    .metadata(metadata)
                    .build();
            results.add(entityDoc);
        }

        return results;
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

