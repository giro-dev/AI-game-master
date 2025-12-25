package dev.agiro.masterserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.controller.WebSocketController;
import dev.agiro.masterserver.dto.ItemGenerationEvent;
import dev.agiro.masterserver.dto.ItemGenerationRequest;
import dev.agiro.masterserver.dto.ItemGenerationResponse;
import dev.agiro.masterserver.dto.WebSocketMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ItemGenerationService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    //private final WebSocketController webSocketController;

    private static final String ITEM_GENERATION_SYSTEM_PROMPT = """
            You are an expert item crafter for tabletop RPG systems.
            You will receive:
            1. A free-text prompt describing desired items
            2. (Optional) system id, actor type, and a blueprint of allowed fields
            3. A target compendium pack id where items will be stored

            Your job is to create 1-3 coherent items that fit the prompt and system.

            Respond ONLY with valid JSON matching this structure:
            {
              "items": [
                {
                  "name": "Item Name",
                  "type": "weapon | armor | equipment | consumable | spell | feature | other",
                  "img": "path/to/icon.png",
                  "system": { ...fields per system/blueprint... }
                }
              ],
              "reasoning": "Brief explanation"
            }
            """;

    public ItemGenerationService(ChatClient.Builder chatClientBuilder,
                                 ObjectMapper objectMapper
                                 //WebSocketController webSocketController
    ) {

        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.7)
                        .build())
                .build();
        this.objectMapper = objectMapper;
        //this.webSocketController = webSocketController;
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

        String sessionId = request.getSessionId();
        sendWsEvent(sessionId, WebSocketMessage.MessageType.ITEM_GENERATION_STARTED,
                ItemGenerationEvent.builder()
                        .requestId(request.getRequestId())
                        .packId(request.getPackId())
                        .status("STARTED")
                        .progress(5)
                        .message("Generating items...")
                        .build());

        try {
            String userPrompt = buildPrompt(request);
            String aiJson = chatClient.prompt()
                    .system(sp -> sp.text(ITEM_GENERATION_SYSTEM_PROMPT))
                    .user(userPrompt)
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

            sendWsEvent(sessionId, WebSocketMessage.MessageType.ITEM_GENERATION_COMPLETED,
                    ItemGenerationEvent.builder()
                            .requestId(request.getRequestId())
                            .packId(request.getPackId())
                            .status("COMPLETED")
                            .progress(100)
                            .items(items)
                            .message("Items generated")
                            .build());
            return response;
        } catch (Exception e) {
            log.error("Item generation failed", e);
            response.setSuccess(false);
            response.setReasoning("Failed to generate items: " + e.getMessage());
            sendWsEvent(sessionId, WebSocketMessage.MessageType.ITEM_GENERATION_FAILED,
                    ItemGenerationEvent.builder()
                            .requestId(request.getRequestId())
                            .packId(request.getPackId())
                            .status("FAILED")
                            .progress(100)
                            .error(e.getMessage())
                            .build());
            return response;
        }
    }

    private void sendWsEvent(String sessionId, WebSocketMessage.MessageType type, ItemGenerationEvent event) {
        if (sessionId == null) return;
        WebSocketMessage wsMessage = WebSocketMessage.success(type, sessionId, event);
        //webSocketController.sendItemUpdate(sessionId, wsMessage);
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
