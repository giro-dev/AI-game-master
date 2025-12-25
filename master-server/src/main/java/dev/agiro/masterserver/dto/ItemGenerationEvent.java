package dev.agiro.masterserver.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Event payloads for item generation progress/completion.
 */
@Data
@Builder
public class ItemGenerationEvent {
    private String requestId;
    private String packId;
    private String status; // e.g. STARTED, COMPLETED, FAILED
    private Integer progress; // 0-100
    private String message;
    private List<Map<String, Object>> items;
    private String error;
}

