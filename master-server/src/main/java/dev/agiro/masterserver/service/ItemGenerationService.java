package dev.agiro.masterserver.service;

import dev.agiro.masterserver.dto.ItemGenerationRequest;
import dev.agiro.masterserver.dto.ItemGenerationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ItemGenerationService {

    private final ChatClient chatClient;
    private final RAGService ragService;

    @Value("classpath:/prompts/item_generation_system.txt")
    private Resource itemGenerationPrompt;

    public ItemGenerationService(ChatClient.Builder chatClientBuilder,
                                 RAGService ragService,
                                 ModelRoutingService modelRoutingService) {

        this.chatClient = chatClientBuilder
                .defaultOptions(modelRoutingService.optionsFor("item-generator"))
                .build();
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
            GeneratedItemsResponse aiResponse = chatClient.prompt()
                    .system(sp -> sp.text(itemGenerationPrompt)
                            .param("language", "en"))
                    .user(u -> u.text("{userPrompt}").param("userPrompt", userPrompt))
                    .call()
                    .entity(GeneratedItemsResponse.class);

            if (aiResponse != null) {
                response.setItems(aiResponse.items() != null ? aiResponse.items() : List.of());
                response.setReasoning(aiResponse.reasoning() != null ? aiResponse.reasoning() : "");
            }
            response.setSuccess(true);
            return response;
        } catch (Exception e) {
            log.error("Item generation failed", e);
            response.setSuccess(false);
            response.setReasoning("Failed to generate items: " + e.getMessage());
            return response;
        }
    }

    /** Structured output record matching the LLM's expected JSON shape. */
    private record GeneratedItemsResponse(List<Map<String, Object>> items, String reasoning) {}

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

}
