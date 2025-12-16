package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagSearchResponse {
    private UUID id;
    private String content;
    private String title;
    private Integer page;
    private String chunkType;
    private String sourceDocument;
    private String foundrySystem;
    private Map<String, Object> metadata;
}

