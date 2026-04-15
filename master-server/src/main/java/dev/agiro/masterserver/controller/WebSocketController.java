package dev.agiro.masterserver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.agent.combat.CombatAgent;
import dev.agiro.masterserver.agent.world.WorldAgent;
import dev.agiro.masterserver.dto.CombatAdviceRequest;
import dev.agiro.masterserver.dto.CombatAdviceResponse;
import dev.agiro.masterserver.dto.EncounterRequest;
import dev.agiro.masterserver.dto.ItemGenerationRequest;
import dev.agiro.masterserver.dto.ItemGenerationResponse;
import dev.agiro.masterserver.dto.LocationRequest;
import dev.agiro.masterserver.dto.WorldEventDto;
import dev.agiro.masterserver.dto.WebSocketMessage;
import dev.agiro.masterserver.agent.item.ItemGenerationService;
import dev.agiro.masterserver.service.TranscriptionService;
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
    private final CombatAgent combatAgent;
    private final WorldAgent worldAgent;
    private final ObjectMapper objectMapper;
    private final TranscriptionService transcriptionService;

    public WebSocketController(SimpMessagingTemplate messagingTemplate,
                               ItemGenerationService itemGenerationService,
                               CombatAgent combatAgent,
                               WorldAgent worldAgent,
                               ObjectMapper objectMapper,
                               TranscriptionService transcriptionService) {
        this.messagingTemplate = messagingTemplate;
        this.itemGenerationService = itemGenerationService;
        this.combatAgent = combatAgent;
        this.worldAgent = worldAgent;
        this.objectMapper = objectMapper;
        this.transcriptionService = transcriptionService;
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
     * Handle audio transcription requests
     */
    @MessageMapping("/audio/transcribe")
    public void handleAudioTranscription(@Payload WebSocketMessage message) {
        log.info("Audio transcription request received: {}", message.getSessionId());
        byte[] audioData = (byte[]) message.getPayload();
        String transcription = transcriptionService.transcribeAudio(audioData);
        WebSocketMessage response = WebSocketMessage.success(
                WebSocketMessage.MessageType.TRANSCRIPTION_COMPLETED,
                message.getSessionId(),
                transcription
        );
        sendToSession(message.getSessionId(), response);
    }

    /**
     * Handle encounter generation requests via WebSocket (async — fires and forgets)
     */
    @MessageMapping("/encounter/generate")
    public void handleEncounterGenerate(@Payload WebSocketMessage message) {
        log.info("Encounter generation request via WS: {}", message.getSessionId());
        EncounterRequest request = objectMapper.convertValue(message.getPayload(), EncounterRequest.class);
        if (request.getSessionId() == null) request.setSessionId(message.getSessionId());
        combatAgent.designEncounterAsync(request);
    }

    /**
     * Handle live combat advice requests via WebSocket (sync — immediate response)
     */
    @MessageMapping("/combat/advise")
    public void handleCombatAdvise(@Payload WebSocketMessage message) {
        log.info("Combat advice request via WS for session: {}", message.getSessionId());
        CombatAdviceRequest request = objectMapper.convertValue(message.getPayload(), CombatAdviceRequest.class);
        if (request.getSessionId() == null) request.setSessionId(message.getSessionId());
        CombatAdviceResponse response = combatAgent.adviseAction(request);
        sendCombatUpdate(message.getSessionId(), WebSocketMessage.success(
                WebSocketMessage.MessageType.COMBAT_ADVICE_COMPLETED, message.getSessionId(), response));
    }

    /**
     * Handle location generation requests via WebSocket (async — fires and forgets)
     */
    @MessageMapping("/world/location/generate")
    public void handleLocationGenerate(@Payload WebSocketMessage message) {
        log.info("Location generation request via WS: {}", message.getSessionId());
        LocationRequest request = objectMapper.convertValue(message.getPayload(), LocationRequest.class);
        if (request.getSessionId() == null) request.setSessionId(message.getSessionId());
        worldAgent.generateLocationAsync(request);
    }

    /**
     * Handle world event logging via WebSocket
     */
    @MessageMapping("/world/event/log")
    public void handleWorldEventLog(@Payload WebSocketMessage message) {
        log.info("World event log request via WS: {}", message.getSessionId());
        WorldEventDto event = objectMapper.convertValue(message.getPayload(), WorldEventDto.class);
        if (event.getSessionId() == null) event.setSessionId(message.getSessionId());
        worldAgent.logEvent(event);
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

    /**
     * Send combat event update (Phase 2)
     */
    public void sendCombatUpdate(String sessionId, WebSocketMessage message) {
        log.info("Sending combat update to session {}: {}", sessionId, message.getType());
        messagingTemplate.convertAndSend("/queue/combat-" + sessionId, message);
    }

    /**
     * Send world event update (Phase 3)
     */
    public void sendWorldUpdate(String sessionId, WebSocketMessage message) {
        log.info("Sending world update to session {}: {}", sessionId, message.getType());
        messagingTemplate.convertAndSend("/queue/world-" + sessionId, message);
    }
}
