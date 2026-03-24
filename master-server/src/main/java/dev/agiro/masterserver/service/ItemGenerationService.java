package dev.agiro.masterserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.ItemGenerationRequest;
import dev.agiro.masterserver.dto.ItemGenerationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ItemGenerationService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final RAGService ragService;

    @Value("classpath:/prompts/item_generation_system.txt")
    private Resource itemGenerationPrompt;

    public ItemGenerationService(ChatClient.Builder chatClientBuilder,
                                 ObjectMapper objectMapper,
                                 RAGService ragService) {

        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.7)
                        .build())
                .build();
        this.objectMapper = objectMapper;
        this.ragService = ragService;
    }

    public ItemGenerationResponse generateItems(ItemGenerationRequest request) {
        ItemGenerationResponse response = new ItemGenerationResponse();
        response.setRequestId(request.getRequestId());
        response.setPackId(request.getPackId());

        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            response.setSuccess(false);
            response.setReasoning("Prompt is required");
            return response;
        }

        try {
            String userPrompt = buildPrompt(request);
            String aiJson = chatClient.prompt()
                    .system(sp -> sp.text(itemGenerationPrompt)
                            .param("language", "en"))
                    .user(u -> u.text("{userPrompt}").param("userPrompt", userPrompt))
                    .call()
                    .content();

            aiJson = cleanJson(aiJson);
            @SuppressWarnings("unchecked")
            Map<String, Object> aiResponse = objectMapper.readValue(aiJson, Map.class);

            List<Map<String, Object>> items = new ArrayList<>();
            if (aiResponse.get("items") instanceof List) {
                items.addAll((List<Map<String, Object>>) aiResponse.get("items"));
            }
            response.setItems(items);
            response.setReasoning((String) aiResponse.getOrDefault("reasoning", ""));
            response.setSuccess(true);
            return response;
        } catch (Exception e) {
            log.error("Item generation failed", e);
            response.setSuccess(false);
            response.setReasoning("Failed to generate items: " + e.getMessage());
            return response;
        }
    }

    private String buildPrompt(ItemGenerationRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Prompt: ").append(request.getPrompt()).append("\n");
        if (request.getSystemId() != null) {
            sb.append("System: ").append(request.getSystemId()).append("\n");
        }
        if (request.getActorType() != null) {
            sb.append("ActorType: ").append(request.getActorType()).append("\n");
        }

        // Tell the AI which item types are valid for this system
        if (request.getValidItemTypes() != null && !request.getValidItemTypes().isEmpty()) {
            sb.append("\nIMPORTANT — Valid item types for this system (use ONLY these): ")
              .append(String.join(", ", request.getValidItemTypes())).append("\n");
            sb.append("The \"type\" field of each item MUST be one of the above values exactly.\n\n");
        }

        // Enrich with RAG context for item definitions and examples
        if (request.getSystemId() != null) {
            String entityExamples = ragService.searchExtractedEntities(
                    request.getPrompt(), request.getSystemId(), request.getWorldId(), null, 4);
            if (!entityExamples.isEmpty()) {
                sb.append("\n=== ITEM EXAMPLES FROM GAME MANUALS ===\n");
                sb.append(entityExamples).append("\n\n");
            }

            String ruleContext = ragService.searchItemContext(
                    List.of("weapon", "armor", "equipment", "spell"),
                    request.getSystemId(), request.getWorldId(), 3);
            if (!ruleContext.isEmpty()) {
                sb.append(ruleContext).append("\n");
            }
        }

        if (request.getBlueprint() != null && !request.getBlueprint().isEmpty()) {
            sb.append("Blueprint: ").append(request.getBlueprint());
        }
        return sb.toString();
    }

    private String cleanJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```json") || trimmed.startsWith("```")) {
            int first = trimmed.indexOf('\n');
            int last = trimmed.lastIndexOf("```");
            if (first >= 0 && last > first) {
                return trimmed.substring(first + 1, last).trim();
            }
        }
        return trimmed;
    }
}
