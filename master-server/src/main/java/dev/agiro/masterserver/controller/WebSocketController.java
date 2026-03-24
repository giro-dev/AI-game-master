package dev.agiro.masterserver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.ItemGenerationRequest;
import dev.agiro.masterserver.dto.ItemGenerationResponse;
import dev.agiro.masterserver.dto.WebSocketMessage;
import dev.agiro.masterserver.service.ItemGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * WebSocket controller for real-time bidirectional communication with Foundry VTT
 */
@Slf4j
@Controller
public class WebSocketController {

    private final SimpMessagingTemplate messagingTemplate;
    private final ItemGenerationService itemGenerationService;
    private final ObjectMapper objectMapper;

    public WebSocketController(SimpMessagingTemplate messagingTemplate,
                               ItemGenerationService itemGenerationService,
                               ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.itemGenerationService = itemGenerationService;
        this.objectMapper = objectMapper;
    }

    /**
     * Handle ping messages from clients
     */
    @MessageMapping("/ping")
    @SendTo("/topic/pong")
    public WebSocketMessage handlePing(@Payload WebSocketMessage message) {
        log.debug("Received ping from session: {}", message.getSessionId());
        return WebSocketMessage.success(
                WebSocketMessage.MessageType.PONG,
                message.getSessionId(),
                "pong"
        );
    }

    /**
     * Handle game events from Foundry
     */
    @MessageMapping("/game-event")
    public void handleGameEvent(@Payload WebSocketMessage message) {
        log.info("Received game event: {} from session: {}", message.getType(), message.getSessionId());

        // Process the game event
        // You can add custom logic here based on the payload

        // Optionally broadcast to all clients or specific topics
        messagingTemplate.convertAndSend("/topic/game-events", message);
    }

    /**
     * Handle item generation requests
     */
    @MessageMapping("/item/generate")
    public void handleItemGenerate(@Payload WebSocketMessage message) {
        log.info("Item generation request via WS: {}", message.getType());
        ItemGenerationRequest request = objectMapper.convertValue(message.getPayload(), ItemGenerationRequest.class);
        if (request.getSessionId() == null) {
            request.setSessionId(message.getSessionId());
        }
        ItemGenerationResponse response = itemGenerationService.generateItems(request);
        if (response.isSuccess()) {
            WebSocketMessage success = WebSocketMessage.success(
                    WebSocketMessage.MessageType.ITEM_GENERATION_COMPLETED,
                    request.getSessionId(),
                    response
            );
            sendItemUpdate(request.getSessionId(), success);
        } else {
            WebSocketMessage error = WebSocketMessage.error(
                    WebSocketMessage.MessageType.ITEM_GENERATION_FAILED,
                    request.getSessionId(),
                    response.getReasoning()
            );
            sendItemUpdate(request.getSessionId(), error);
        }
    }

    /**
     * Send a notification to a specific session
     */
    public void sendToSession(String sessionId, WebSocketMessage message) {
        messagingTemplate.convertAndSend("/queue/" + sessionId, message);
    }

    /**
     * Broadcast a message to all connected clients
     */
    public void broadcast(String topic, WebSocketMessage message) {
        messagingTemplate.convertAndSend("/topic/" + topic, message);
    }

    /**
     * Send character generation update
     */
    public void sendCharacterUpdate(String sessionId, WebSocketMessage message) {
        log.info("Sending character update to session {}: {}", sessionId, message.getType());
        messagingTemplate.convertAndSend("/queue/character-" + sessionId, message);
    }

    /**
     * Send image generation update
     */
    public void sendImageUpdate(String sessionId, WebSocketMessage message) {
        log.info("Sending image update to session {}: {}", sessionId, message.getType());
        messagingTemplate.convertAndSend("/queue/image-" + sessionId, message);
    }

    /**
     * Send item generation update
     */
    public void sendItemUpdate(String sessionId, WebSocketMessage message) {
        log.info("Sending item update to session {}: {}", sessionId, message.getType());
        messagingTemplate.convertAndSend("/queue/item-" + sessionId, message);
    }

    /**
     * Send book ingestion update
     */
    public void sendIngestionUpdate(String sessionId, WebSocketMessage message) {
        log.info("Sending ingestion update to session {}: {}", sessionId, message.getType());
        messagingTemplate.convertAndSend("/queue/ingestion-" + sessionId, message);
    }
}
