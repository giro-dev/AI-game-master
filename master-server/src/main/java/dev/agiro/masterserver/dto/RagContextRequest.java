package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagContextRequest {
    private String query;
    private Integer topK;
    private String foundrySystem;
    private String sourceDocument;
}

