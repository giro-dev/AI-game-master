package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Base WebSocket message for communication between server and Foundry
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketMessage {

    /**
     * Message type identifier
     */
    private MessageType type;

    /**
     * Session or client identifier
     */
    private String sessionId;

    /**
     * Message payload (can be any data)
     */
    private Object payload;

    /**
     * Optional error message if applicable
     */
    private String error;

    /**
     * Timestamp of message creation
     */
    private Long timestamp;

    public enum MessageType {
        // Character related events
        CHARACTER_GENERATION_STARTED,
        CHARACTER_GENERATION_COMPLETED,
        CHARACTER_GENERATION_FAILED,

        // Image related events
        IMAGE_GENERATION_STARTED,
        IMAGE_GENERATION_COMPLETED,
        IMAGE_GENERATION_FAILED,

        // Item related events
        ITEM_GENERATION_REQUEST,
        ITEM_GENERATION_STARTED,
        ITEM_GENERATION_COMPLETED,
        ITEM_GENERATION_FAILED,

        // Book ingestion events
        INGESTION_STARTED,
        INGESTION_PROGRESS,
        INGESTION_COMPLETED,
        INGESTION_FAILED,

        // Transcription events
        TRANSCRIPTION_COMPLETED,

        // Generic notifications
        NOTIFICATION,
        ERROR,

        // Client to server messages
        PING,
        PONG,

        // Custom game events
        GAME_EVENT
    }

    /**
     * Create a success message
     */
    public static WebSocketMessage success(MessageType type, String sessionId, Object payload) {
        return WebSocketMessage.builder()
                .type(type)
                .sessionId(sessionId)
                .payload(payload)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Create an error message
     */
    public static WebSocketMessage error(MessageType type, String sessionId, String error) {
        return WebSocketMessage.builder()
                .type(type)
                .sessionId(sessionId)
                .error(error)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
