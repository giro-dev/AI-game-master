package dev.agiro.masterserver.dto;

import lombok.Data;

import java.util.Map;

@Data
public class CreateCharacterResponse {
    private CharacterDataDto character;
    private String reasoning;
    private Boolean success;

    @Data
    public static class CharacterDataDto {
        private ActorDto actor;
        private java.util.List<ItemDto> items;
    }

    @Data
    public static class ActorDto {
        private String name;
        private String type;
        private String img;
        private Map<String, Object> system;
    }

    @Data
    public static class ItemDto {
        private String name;
        private String type;
        private String img;
        private Map<String, Object> system;
    }
}

