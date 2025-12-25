package dev.agiro.masterserver.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Response containing generated items and optional reasoning.
 */
@Data
public class ItemGenerationResponse {
    private boolean success;
    private String reasoning;
    private String requestId;
    private String packId;
    private List<Map<String, Object>> items;
}

