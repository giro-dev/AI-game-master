package dev.agiro.masterserver.dto;

import lombok.Data;

import java.util.List;

@Data
public class CharacterBlueprintDto {
    private String systemId;
    private String systemVersion;
    private String actorType;
    private Long timestamp;

    // New simplified structure from Foundry (flat array of fields)
    private List<Object> actorFields;
    
    // Legacy structure (nested)
    private ActorBlueprintDto actor;
    private List<ItemTypeDto> availableItems;
    private List<String> constraints;
    private List<FieldDto> coreFields;
    private ExampleDto example;

    @Data
    public static class ActorBlueprintDto {
        private String type;
        private List<FieldDto> fields;
    }

    @Data
    public static class ItemTypeDto {
        private String type;
        private String label;
        private List<FieldDto> fields;
        private Boolean repeatable;
    }

    @Data
    public static class FieldDto {
        private String path;
        private String type;
        private String label;
        private Boolean required;
        private Object defaultValue;
        private Object min;
        private Object max;
        private Object choices;
    }

    @Data
    public static class ExampleDto {
        private Object actor;
        private List<Object> items;
    }
}

