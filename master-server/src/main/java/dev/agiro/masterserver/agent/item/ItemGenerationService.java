package dev.agiro.masterserver.agent.item;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.controller.WebSocketController;
import dev.agiro.masterserver.dto.ItemGenerationEvent;
import dev.agiro.masterserver.dto.ItemGenerationRequest;
import dev.agiro.masterserver.dto.ItemGenerationResponse;
import dev.agiro.masterserver.dto.WebSocketMessage;
import dev.agiro.masterserver.tool.RAGService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Item Generation Service — generates Foundry VTT-compatible items from a free-text prompt.
 * <p>
 * Generation is fully asynchronous: the caller receives an immediate acknowledgement and
 * result delivery is handled via WebSocket ({@code /queue/item-<sessionId>}).
 * <p>
 * The service enriches item generation with:
 * <ul>
 *   <li>Item examples retrieved from the RAG knowledge base (ingested manuals).</li>
 *   <li>Valid item type constraints from the Foundry module's blueprint.</li>
 *   <li>System-specific field schemas to ensure structural compatibility.</li>
 * </ul>
 */
@Slf4j
@Service
public class ItemGenerationService {

    @Value("classpath:/prompts/item_generation_system.txt")
    private Resource itemGenerationPrompt;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final RAGService ragService;
    private final WebSocketController webSocketController;

    public ItemGenerationService(ChatClient.Builder chatClientBuilder,
                                  ObjectMapper objectMapper,
                                  RAGService ragService,
                                  WebSocketController webSocketController) {
        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder().model("gpt-4.1-mini").temperature(0.85).build())
                .build();
        this.objectMapper = objectMapper;
        this.ragService = ragService;
        this.webSocketController = webSocketController;
    }

    // ── Synchronous API (used by WebSocketController for WS-initiated requests) ──

    /**
     * Generate items synchronously. Used when the caller (e.g. WebSocketController)
     * manages the async boundary itself.
     */
    public ItemGenerationResponse generateItems(ItemGenerationRequest request) {
        log.info("[ItemGenerationService] Generating items for prompt: '{}' system={}",
                request.getPrompt(), request.getSystemId());

        try {
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(request);

            String raw = chatClient.prompt()
                    .system(systemPrompt)
                    .user(u -> u.text("{p}").param("p", userPrompt))
                    .call().content();

            return parseResponse(raw, request);

        } catch (Exception e) {
            log.error("[ItemGenerationService] Generation failed", e);
            ItemGenerationResponse err = new ItemGenerationResponse();
            err.setSuccess(false);
            err.setReasoning("Item generation failed: " + e.getMessage());
            err.setRequestId(request.getRequestId());
            err.setPackId(request.getPackId());
            err.setItems(List.of());
            return err;
        }
    }

    // ── Async API (used by REST endpoints that want fire-and-forget) ──────

    /**
     * Generate items asynchronously and push results via WebSocket.
     * Returns immediately; the Foundry module will receive results on
     * {@code /queue/item-<sessionId>} with message type {@code ITEM_GENERATION_COMPLETED}.
     */
    @Async
    public void generateItemsAsync(ItemGenerationRequest request) {
        String sessionId = request.getSessionId();

        // Notify start
        if (sessionId != null) {
            webSocketController.sendItemUpdate(sessionId, WebSocketMessage.success(
                    WebSocketMessage.MessageType.ITEM_GENERATION_STARTED, sessionId,
                    Map.of("message", "Item generation started", "prompt", request.getPrompt())));
        }

        try {
            ItemGenerationResponse response = generateItems(request);

            if (sessionId != null) {
                WebSocketMessage.MessageType type = response.isSuccess()
                        ? WebSocketMessage.MessageType.ITEM_GENERATION_COMPLETED
                        : WebSocketMessage.MessageType.ITEM_GENERATION_FAILED;

                ItemGenerationEvent event = ItemGenerationEvent.builder()
                        .requestId(response.getRequestId())
                        .packId(response.getPackId())
                        .items(response.getItems())
                        .status(response.isSuccess() ? "COMPLETED" : "FAILED")
                        .message(response.getReasoning())
                        .build();

                webSocketController.sendItemUpdate(sessionId,
                        response.isSuccess()
                                ? WebSocketMessage.success(type, sessionId, event)
                                : WebSocketMessage.error(type, sessionId, response.getReasoning()));
            }

        } catch (Exception e) {
            log.error("[ItemGenerationService] Async generation failed for session {}", sessionId, e);
            if (sessionId != null) {
                webSocketController.sendItemUpdate(sessionId, WebSocketMessage.error(
                        WebSocketMessage.MessageType.ITEM_GENERATION_FAILED, sessionId,
                        "Item generation failed: " + e.getMessage()));
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private String buildSystemPrompt() {
        try {
            return itemGenerationPrompt.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                    .replace("{language}", "en");
        } catch (Exception e) {
            return "You are an expert item creator for tabletop RPGs. " +
                   "Respond ONLY with valid JSON: {\"items\": [...], \"reasoning\": \"...\"}";
        }
    }

    private String buildUserPrompt(ItemGenerationRequest request) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== ITEM REQUEST ===\n");
        sb.append("Prompt: ").append(request.getPrompt()).append("\n");
        if (request.getSystemId() != null) {
            sb.append("Game system: ").append(request.getSystemId()).append("\n");
        }
        if (request.getPackId() != null) {
            sb.append("Target compendium: ").append(request.getPackId()).append("\n");
        }
        sb.append("\n");

        // Valid item types (critical constraint)
        if (request.getValidItemTypes() != null && !request.getValidItemTypes().isEmpty()) {
            sb.append("=== VALID ITEM TYPES (you MUST use one of these) ===\n");
            request.getValidItemTypes().forEach(t -> sb.append("  - ").append(t).append("\n"));
            sb.append("\n");
        }

        // Item blueprint/schema
        if (request.getBlueprint() != null && !request.getBlueprint().isEmpty()) {
            sb.append("=== ITEM SCHEMA ===\n");
            try {
                sb.append(truncate(objectMapper.writeValueAsString(request.getBlueprint()), 1500)).append("\n\n");
            } catch (Exception e) {
                sb.append("[schema unavailable]\n\n");
            }
        }

        // RAG item examples
        if (request.getSystemId() != null) {
            String itemContext = ragService.searchItemContextWithCompendium(
                    request.getPrompt(),
                    request.getSystemId(),
                    request.getWorldId(),
                    4
            );
            if (!itemContext.isBlank()) {
                sb.append("=== ITEM EXAMPLES FROM MANUALS ===\n")
                  .append(truncate(itemContext, 2000)).append("\n\n");
            }
        }

        sb.append("Generate 1-3 items matching the request. Use appropriate system fields based on the examples.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private ItemGenerationResponse parseResponse(String raw, ItemGenerationRequest request) {
        ItemGenerationResponse response = new ItemGenerationResponse();
        response.setRequestId(request.getRequestId());
        response.setPackId(request.getPackId());

        try {
            Map<String, Object> parsed = objectMapper.readValue(cleanJson(raw), new TypeReference<>() {});
            Object itemsObj = parsed.get("items");
            List<Map<String, Object>> items = new ArrayList<>();

            if (itemsObj instanceof List<?> rawList) {
                for (Object item : rawList) {
                    if (item instanceof Map<?, ?> m) {
                        Map<String, Object> itemMap = new LinkedHashMap<>((Map<String, Object>) m);

                        // Validate/fix item type
                        if (request.getValidItemTypes() != null && !request.getValidItemTypes().isEmpty()) {
                            String type = (String) itemMap.get("type");
                            if (type == null || !request.getValidItemTypes().contains(type)) {
                                itemMap.put("type", request.getValidItemTypes().getFirst());
                            }
                        }
                        items.add(itemMap);
                    }
                }
            }

            response.setItems(items);
            response.setReasoning((String) parsed.getOrDefault("reasoning", "Generated successfully"));
            response.setSuccess(!items.isEmpty());

        } catch (Exception e) {
            log.warn("[ItemGenerationService] Failed to parse item response: {}", e.getMessage());
            response.setSuccess(false);
            response.setReasoning("Failed to parse response: " + e.getMessage());
            response.setItems(List.of());
        }

        return response;
    }

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String t = raw.trim();
        if (t.startsWith("```json")) t = t.substring(7);
        else if (t.startsWith("```")) t = t.substring(3);
        if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        return t.trim();
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
