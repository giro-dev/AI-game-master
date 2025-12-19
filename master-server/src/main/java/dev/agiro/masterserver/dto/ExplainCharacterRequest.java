package dev.agiro.masterserver.dto;

import lombok.Data;

import java.util.Map;

@Data
public class ExplainCharacterRequest {
    private CharacterDataDto characterData;
    private String systemId;

    @Data
    public static class CharacterDataDto {
        private Map<String, Object> actor;
        private java.util.List<Map<String, Object>> items;
    }
}

