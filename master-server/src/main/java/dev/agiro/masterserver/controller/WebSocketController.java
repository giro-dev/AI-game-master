package dev.agiro.masterserver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.DirectorRequest;
import dev.agiro.masterserver.dto.DirectorResponse;
import dev.agiro.masterserver.dto.ItemGenerationRequest;
import dev.agiro.masterserver.dto.ItemGenerationResponse;
import dev.agiro.masterserver.dto.NpcDialogueDto;
import dev.agiro.masterserver.dto.WebSocketMessage;
import dev.agiro.masterserver.service.AdventureDirectorService;
import dev.agiro.masterserver.service.ItemGenerationService;
import dev.agiro.masterserver.service.TranscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket controller for real-time bidirectional communication with Foundry VTT
 */
@Slf4j
@Controller
public class WebSocketController {

    private final SimpMessagingTemplate messagingTemplate;
    private final ItemGenerationService itemGenerationService;
    private final ObjectMapper objectMapper;
    private final TranscriptionService transcriptionService;
    private final AdventureDirectorService adventureDirectorService;

    public WebSocketController(SimpMessagingTemplate messagingTemplate,
                               ItemGenerationService itemGenerationService,
                               ObjectMapper objectMapper,
                               TranscriptionService transcriptionService,
                               AdventureDirectorService adventureDirectorService) {
        this.messagingTemplate = messagingTemplate;
        this.itemGenerationService = itemGenerationService;
        this.objectMapper = objectMapper;
        this.transcriptionService = transcriptionService;
        this.adventureDirectorService = adventureDirectorService;
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

    /* ────────────────────────────────────────────────────────────────────
     * Adventure Director
     * ────────────────────────────────────────────────────────────────────*/

    /**
     * Handle a streamed transcription chunk (or completed transcription) from the client.
     * Payload may either contain an already-transcribed {@code transcription} string or
     * the raw audio in {@code audioBase64}; in the latter case we run STT first.
     */
    @MessageMapping("/adventure/transcription")
    public void handleAdventureTranscription(@Payload WebSocketMessage message) {
        log.info("Adventure transcription request from session {}", message.getSessionId());
        DirectorRequest request = objectMapper.convertValue(message.getPayload(), DirectorRequest.class);

        if ((request.getTranscription() == null || request.getTranscription().isBlank())
                && message.getPayload() instanceof Map<?, ?> raw
                && raw.get("audioBase64") instanceof String b64) {
            byte[] audio = Base64.getDecoder().decode(b64);
            request.setTranscription(transcriptionService.transcribeAudio(audio));
        }

        // Echo the transcription so the UI can show what we heard.
        Map<String, Object> trPayload = new HashMap<>();
        trPayload.put("transcription", request.getTranscription());
        trPayload.put("adventureSessionId", request.getAdventureSessionId());
        sendAdventureUpdate(request.getAdventureSessionId(), WebSocketMessage.success(
                WebSocketMessage.MessageType.TRANSCRIPTION_RECEIVED,
                request.getAdventureSessionId(),
                trPayload));

        DirectorResponse response = adventureDirectorService.process(request);
        dispatchDirectorResponse(request.getAdventureSessionId(), response);
    }

    /** Resolve a previously-sent INTENT_CONFIRMATION_REQUEST with the player's reply. */
    @MessageMapping("/adventure/confirm")
    public void handleAdventureConfirm(@Payload WebSocketMessage message) {
        log.info("Adventure confirmation from session {}", message.getSessionId());
        DirectorRequest request = objectMapper.convertValue(message.getPayload(), DirectorRequest.class);

        WebSocketMessage.MessageType ack =
                request.getConfirmationResponse() != null
                        && (request.getConfirmationResponse().toLowerCase().startsWith("y")
                            || "si".equalsIgnoreCase(request.getConfirmationResponse())
                            || "sí".equalsIgnoreCase(request.getConfirmationResponse()))
                ? WebSocketMessage.MessageType.INTENT_CONFIRMED
                : WebSocketMessage.MessageType.INTENT_REJECTED;
        sendAdventureUpdate(request.getAdventureSessionId(), WebSocketMessage.success(
                ack, request.getAdventureSessionId(), Map.of("response", request.getConfirmationResponse())));

        DirectorResponse response = adventureDirectorService.process(request);
        dispatchDirectorResponse(request.getAdventureSessionId(), response);
    }

    public void sendDirectorUpdate(String sessionId, WebSocketMessage message) {
        log.info("Sending director update to adventure session {}: {}", sessionId, message.getType());
        sendAdventureUpdate(sessionId, message);
    }

    public void sendNpcAudio(String sessionId, WebSocketMessage message) {
        log.info("Sending NPC audio to adventure session {}", sessionId);
        sendAdventureUpdate(sessionId, message);
    }

    private void dispatchDirectorResponse(String sessionId, DirectorResponse response) {
        if (sessionId == null || response == null) return;

        if (response.isConfirmationNeeded()) {
            sendAdventureUpdate(sessionId, WebSocketMessage.success(
                    WebSocketMessage.MessageType.INTENT_CONFIRMATION_REQUEST,
                    sessionId,
                    Map.of(
                            "question", response.getConfirmationQuestion(),
                            "reasoning", response.getReasoning() == null ? "" : response.getReasoning()
                    )));
            return;
        }

        sendAdventureUpdate(sessionId, WebSocketMessage.success(
                WebSocketMessage.MessageType.DIRECTOR_NARRATION,
                sessionId,
                Map.of(
                        "narration", response.getNarration() == null ? "" : response.getNarration(),
                        "actions", response.getActions(),
                        "reasoning", response.getReasoning() == null ? "" : response.getReasoning()
                )));

        if (response.getNpcDialogues() != null) {
            for (NpcDialogueDto d : response.getNpcDialogues()) {
                sendAdventureUpdate(sessionId, WebSocketMessage.success(
                        WebSocketMessage.MessageType.NPC_DIALOGUE_AUDIO,
                        sessionId,
                        d));
            }
        }

        if (response.getStateUpdates() != null) {
            sendAdventureUpdate(sessionId, WebSocketMessage.success(
                    WebSocketMessage.MessageType.ADVENTURE_STATE_UPDATE,
                    sessionId,
                    response.getStateUpdates()));
            if (response.getStateUpdates().getTransitionTriggered() != null
                    && !response.getStateUpdates().getTransitionTriggered().isBlank()) {
                sendAdventureUpdate(sessionId, WebSocketMessage.success(
                        WebSocketMessage.MessageType.SCENE_TRANSITION,
                        sessionId,
                        Map.of("targetSceneId", response.getStateUpdates().getTransitionTriggered())));
            }
        }
    }

    private void sendAdventureUpdate(String sessionId, WebSocketMessage message) {
        if (sessionId == null) return;
        messagingTemplate.convertAndSend("/queue/adventure-" + sessionId, message);
    }
}
