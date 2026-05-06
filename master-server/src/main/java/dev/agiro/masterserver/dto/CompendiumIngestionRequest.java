package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompendiumIngestionRequest {
    private String worldId;
    private String foundrySystem;
    private String packId;
    private String packLabel;
    private String documentType;
    private String sessionId;

    @Builder.Default
    private List<EntryDto> entries = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntryDto {
        private String id;
        private String name;
        private String type;
        private String text;
        private Map<String, Object> metadata;
    }
}
