package dev.agiro.masterserver.service;

import dev.agiro.masterserver.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the character and item creation flow improvements.
 * Validates DTOs, validation logic, batch request handling,
 * and data structures without requiring a running server or LLM.
 */
class CharacterCreationFlowTest {

    /* ================================================================== */
    /*  DTO Construction Tests                                              */
    /* ================================================================== */

    @Nested
    @DisplayName("DTO construction and serialization")
    class DtoTests {

        @Test
        @DisplayName("CreateCharacterRequest holds all required fields")
        void createCharacterRequestFields() {
            CreateCharacterRequest req = new CreateCharacterRequest();
            req.setPrompt("A brave warrior");
            req.setActorType("character");
            req.setLanguage("en");
            req.setWorldId("world-1");
            req.setSessionId("session-abc");

            CharacterBlueprintDto bp = new CharacterBlueprintDto();
            bp.setSystemId("dnd5e");
            bp.setActorType("character");
            req.setBlueprint(bp);

            assertEquals("A brave warrior", req.getPrompt());
            assertEquals("character", req.getActorType());
            assertEquals("en", req.getLanguage());
            assertEquals("world-1", req.getWorldId());
            assertEquals("session-abc", req.getSessionId());
            assertNotNull(req.getBlueprint());
            assertEquals("dnd5e", req.getBlueprint().getSystemId());
        }

        @Test
        @DisplayName("CreateCharacterResponse assembles actor + items")
        void createCharacterResponseAssembly() {
            CreateCharacterResponse resp = new CreateCharacterResponse();
            resp.setSuccess(true);
            resp.setReasoning("Test reasoning");

            CreateCharacterResponse.ActorDto actor = new CreateCharacterResponse.ActorDto();
            actor.setName("Test Character");
            actor.setType("character");
            actor.setImg("icons/svg/mystery-man.svg");

            Map<String, Object> system = new HashMap<>();
            system.put("strength", 16);
            system.put("dexterity", 14);
            actor.setSystem(system);

            CreateCharacterResponse.ItemDto item = new CreateCharacterResponse.ItemDto();
            item.setName("Longsword");
            item.setType("weapon");
            item.setSystem(Map.of("damage", "1d8", "weight", 3));

            CreateCharacterResponse.CharacterDataDto charData = new CreateCharacterResponse.CharacterDataDto();
            charData.setActor(actor);
            charData.setItems(List.of(item));
            resp.setCharacter(charData);

            assertTrue(resp.getSuccess());
            assertEquals("Test Character", resp.getCharacter().getActor().getName());
            assertEquals(1, resp.getCharacter().getItems().size());
            assertEquals("Longsword", resp.getCharacter().getItems().get(0).getName());
            assertEquals(16, resp.getCharacter().getActor().getSystem().get("strength"));
        }

        @Test
        @DisplayName("BatchCharacterRequest defaults to count=1")
        void batchRequestDefaults() {
            BatchCharacterRequest req = new BatchCharacterRequest();
            assertEquals(1, req.getCount());
            assertNull(req.getVariationMode());
        }

        @Test
        @DisplayName("BatchCharacterResponse tracks generated vs requested")
        void batchResponseTracking() {
            BatchCharacterResponse resp = new BatchCharacterResponse();
            resp.setRequested(5);
            resp.setGenerated(3);
            resp.setSuccess(true);

            CreateCharacterResponse char1 = new CreateCharacterResponse();
            char1.setSuccess(true);
            resp.getCharacters().add(char1);

            resp.getErrors().add("Character 4: Timeout");
            resp.getErrors().add("Character 5: Timeout");

            assertEquals(5, resp.getRequested());
            assertEquals(3, resp.getGenerated());
            assertEquals(1, resp.getCharacters().size());
            assertEquals(2, resp.getErrors().size());
        }
    }

    /* ================================================================== */
    /*  Validation Logic Tests                                              */
    /* ================================================================== */

    @Nested
    @DisplayName("Character validation against blueprint constraints")
    class ValidationTests {

        @Test
        @DisplayName("Valid character passes validation with no errors")
        void validCharacterPassesValidation() {
            ValidateCharacterResponse response = new ValidateCharacterResponse();

            CharacterBlueprintDto blueprint = buildTestBlueprint();
            Map<String, Object> characterData = buildValidCharacterData();

            new CharacterGenerationServiceTestHelper().validateAgainstBlueprint(
                    characterData, blueprint, response);

            assertTrue(response.getErrors().isEmpty(),
                    "Expected no errors but got: " + response.getErrors());
        }

        @Test
        @DisplayName("Detects value below minimum constraint")
        void detectsBelowMinimum() {
            ValidateCharacterResponse response = new ValidateCharacterResponse();

            CharacterBlueprintDto blueprint = buildTestBlueprint();
            Map<String, Object> characterData = buildValidCharacterData();
            // Set strength below minimum (min=1)
            ((Map<String, Object>) characterData.get("atributos")).put("strength", -5);

            new CharacterGenerationServiceTestHelper().validateAgainstBlueprint(
                    characterData, blueprint, response);

            assertFalse(response.getErrors().isEmpty());
            assertTrue(response.getErrors().stream()
                    .anyMatch(e -> e.getField().contains("strength") && e.getMessage().contains("below minimum")));
        }

        @Test
        @DisplayName("Detects value above maximum constraint")
        void detectsAboveMaximum() {
            ValidateCharacterResponse response = new ValidateCharacterResponse();

            CharacterBlueprintDto blueprint = buildTestBlueprint();
            Map<String, Object> characterData = buildValidCharacterData();
            ((Map<String, Object>) characterData.get("atributos")).put("strength", 999);

            new CharacterGenerationServiceTestHelper().validateAgainstBlueprint(
                    characterData, blueprint, response);

            assertFalse(response.getErrors().isEmpty());
            assertTrue(response.getErrors().stream()
                    .anyMatch(e -> e.getField().contains("strength") && e.getMessage().contains("exceeds maximum")));
        }

        @Test
        @DisplayName("Detects missing required field")
        void detectsMissingRequiredField() {
            ValidateCharacterResponse response = new ValidateCharacterResponse();

            CharacterBlueprintDto blueprint = buildTestBlueprint();
            Map<String, Object> characterData = new HashMap<>();
            characterData.put("atributos", new HashMap<>(Map.of("dexterity", 14)));
            // Missing "strength" which is required

            new CharacterGenerationServiceTestHelper().validateAgainstBlueprint(
                    characterData, blueprint, response);

            assertFalse(response.getErrors().isEmpty());
            assertTrue(response.getErrors().stream()
                    .anyMatch(e -> e.getMessage().contains("Required field")));
        }

        @Test
        @DisplayName("ValidateCharacterResponse marks valid/invalid correctly")
        void validateResponseStatus() {
            ValidateCharacterResponse resp = new ValidateCharacterResponse();
            assertTrue(resp.getErrors().isEmpty());

            resp.getErrors().add(new ValidateCharacterResponse.ValidationError(
                    "system.hp", "Value too high", "error"));
            assertFalse(resp.getErrors().isEmpty());
            assertEquals("system.hp", resp.getErrors().get(0).getField());
            assertEquals("error", resp.getErrors().get(0).getSeverity());
        }

        private CharacterBlueprintDto buildTestBlueprint() {
            CharacterBlueprintDto bp = new CharacterBlueprintDto();
            bp.setSystemId("test-system");
            bp.setActorType("character");

            Map<String, Object> strengthField = new HashMap<>();
            strengthField.put("path", "atributos.strength");
            strengthField.put("type", "number");
            strengthField.put("required", true);
            strengthField.put("min", 1);
            strengthField.put("max", 20);

            Map<String, Object> dexField = new HashMap<>();
            dexField.put("path", "atributos.dexterity");
            dexField.put("type", "number");
            dexField.put("required", false);
            dexField.put("min", 1);
            dexField.put("max", 20);

            bp.setActorFields(List.of(strengthField, dexField));
            return bp;
        }

        private Map<String, Object> buildValidCharacterData() {
            Map<String, Object> data = new HashMap<>();
            Map<String, Object> atributos = new HashMap<>();
            atributos.put("strength", 16);
            atributos.put("dexterity", 14);
            data.put("atributos", atributos);
            return data;
        }
    }

    /* ================================================================== */
    /*  Nested Structure Building Tests                                     */
    /* ================================================================== */

    @Nested
    @DisplayName("Nested structure building from flat paths")
    class NestedStructureTests {

        @Test
        @DisplayName("Builds nested map from dot-notation paths")
        void buildsNestedFromFlat() {
            Map<String, Object> flat = new HashMap<>();
            flat.put("system.atributos.strength", 16);
            flat.put("system.atributos.dexterity", 14);
            flat.put("system.hp.value", 45);
            flat.put("system.hp.max", 45);

            Map<String, Object> nested = buildNestedStructure(flat);

            @SuppressWarnings("unchecked")
            Map<String, Object> atributos = (Map<String, Object>) nested.get("atributos");
            assertNotNull(atributos);
            assertEquals(16, atributos.get("strength"));
            assertEquals(14, atributos.get("dexterity"));

            @SuppressWarnings("unchecked")
            Map<String, Object> hp = (Map<String, Object>) nested.get("hp");
            assertNotNull(hp);
            assertEquals(45, hp.get("value"));
            assertEquals(45, hp.get("max"));
        }

        @Test
        @DisplayName("Handles single-level paths")
        void handlesSingleLevel() {
            Map<String, Object> flat = new HashMap<>();
            flat.put("system.name", "Test");

            Map<String, Object> nested = buildNestedStructure(flat);
            assertEquals("Test", nested.get("name"));
        }

        @Test
        @DisplayName("Handles deeply nested paths")
        void handlesDeeplyNested() {
            Map<String, Object> flat = new HashMap<>();
            flat.put("system.a.b.c.d.value", 42);

            Map<String, Object> nested = buildNestedStructure(flat);

            @SuppressWarnings("unchecked")
            Map<String, Object> level1 = (Map<String, Object>) nested.get("a");
            assertNotNull(level1);
            @SuppressWarnings("unchecked")
            Map<String, Object> level2 = (Map<String, Object>) level1.get("b");
            assertNotNull(level2);
            @SuppressWarnings("unchecked")
            Map<String, Object> level3 = (Map<String, Object>) level2.get("c");
            assertNotNull(level3);
            @SuppressWarnings("unchecked")
            Map<String, Object> level4 = (Map<String, Object>) level3.get("d");
            assertNotNull(level4);
            assertEquals(42, level4.get("value"));
        }

        /** Reimplementation of CharacterGenerationService.buildNestedStructure for testing */
        private Map<String, Object> buildNestedStructure(Map<String, Object> flatData) {
            Map<String, Object> nested = new HashMap<>();
            for (Map.Entry<String, Object> entry : flatData.entrySet()) {
                String path = entry.getKey();
                if (path.startsWith("system.")) path = path.substring(7);
                setNestedValue(nested, path, entry.getValue());
            }
            return nested;
        }

        @SuppressWarnings("unchecked")
        private void setNestedValue(Map<String, Object> map, String path, Object value) {
            String[] parts = path.split("\\.");
            Map<String, Object> current = map;
            for (int i = 0; i < parts.length - 1; i++) {
                current.computeIfAbsent(parts[i], k -> new HashMap<String, Object>());
                Object next = current.get(parts[i]);
                if (!(next instanceof Map)) {
                    next = new HashMap<String, Object>();
                    current.put(parts[i], next);
                }
                current = (Map<String, Object>) next;
            }
            current.put(parts[parts.length - 1], value);
        }
    }

    /* ================================================================== */
    /*  Item Generation Response Tests                                      */
    /* ================================================================== */

    @Nested
    @DisplayName("Item generation response handling")
    class ItemGenerationTests {

        @Test
        @DisplayName("ItemGenerationResponse holds items list")
        void itemResponseHoldsItems() {
            ItemGenerationResponse resp = new ItemGenerationResponse();
            resp.setSuccess(true);
            resp.setReasoning("Generated 3 items");
            resp.setPackId("world.my-items");

            resp.setItems(List.of(
                    Map.of("name", "Healing Potion", "type", "consumable"),
                    Map.of("name", "Fire Sword", "type", "weapon"),
                    Map.of("name", "Chain Mail", "type", "armor")
            ));

            assertTrue(resp.isSuccess());
            assertEquals(3, resp.getItems().size());
            assertEquals("Healing Potion", resp.getItems().get(0).get("name"));
            assertEquals("world.my-items", resp.getPackId());
        }

        @Test
        @DisplayName("Empty item list is valid")
        void emptyItemListIsValid() {
            ItemGenerationResponse resp = new ItemGenerationResponse();
            resp.setSuccess(true);
            resp.setItems(List.of());

            assertTrue(resp.isSuccess());
            assertEquals(0, resp.getItems().size());
        }
    }

    /* ================================================================== */
    /*  Batch Request Construction Tests                                    */
    /* ================================================================== */

    @Nested
    @DisplayName("Batch character request construction")
    class BatchRequestTests {

        @Test
        @DisplayName("Batch request with multiple characters")
        void batchRequestMultiple() {
            BatchCharacterRequest req = new BatchCharacterRequest();
            req.setPrompt("Create diverse NPCs for a tavern scene");
            req.setActorType("npc");
            req.setCount(5);
            req.setVariationMode("diverse");
            req.setLanguage("en");

            assertEquals(5, req.getCount());
            assertEquals("diverse", req.getVariationMode());
            assertEquals("npc", req.getActorType());
        }

        @Test
        @DisplayName("Batch response aggregates results and errors")
        void batchResponseAggregation() {
            BatchCharacterResponse resp = new BatchCharacterResponse();
            resp.setRequested(3);

            // Simulate 2 successful + 1 failed
            CreateCharacterResponse success1 = new CreateCharacterResponse();
            success1.setSuccess(true);
            CreateCharacterResponse success2 = new CreateCharacterResponse();
            success2.setSuccess(true);

            resp.getCharacters().add(success1);
            resp.getCharacters().add(success2);
            resp.getErrors().add("Character 3: Generation timeout");
            resp.setGenerated(2);
            resp.setSuccess(true);

            assertEquals(3, resp.getRequested());
            assertEquals(2, resp.getGenerated());
            assertEquals(2, resp.getCharacters().size());
            assertEquals(1, resp.getErrors().size());
            assertTrue(resp.isSuccess());
        }
    }

    /* ================================================================== */
    /*  Validate Request/Response Tests                                     */
    /* ================================================================== */

    @Nested
    @DisplayName("Validation request and response")
    class ValidateRequestTests {

        @Test
        @DisplayName("Validate request carries all fields")
        void validateRequestFields() {
            ValidateCharacterRequest req = new ValidateCharacterRequest();
            req.setSystemId("coc7");
            req.setActorType("character");
            req.setCharacterData(Map.of("strength", 50, "dexterity", 65));

            assertEquals("coc7", req.getSystemId());
            assertEquals("character", req.getActorType());
            assertNotNull(req.getCharacterData());
        }

        @Test
        @DisplayName("Validation errors include severity levels")
        void validationErrorSeverity() {
            ValidateCharacterResponse.ValidationError error =
                    new ValidateCharacterResponse.ValidationError("system.hp", "Too low", "warning");

            assertEquals("system.hp", error.getField());
            assertEquals("Too low", error.getMessage());
            assertEquals("warning", error.getSeverity());
        }
    }

    /* ================================================================== */
    /*  Helper – validation logic extracted for testing                      */
    /* ================================================================== */

    /**
     * Replicates the validation logic from CharacterGenerationService
     * so it can be tested without Spring context / ChatClient.
     */
    static class CharacterGenerationServiceTestHelper {

        @SuppressWarnings("unchecked")
        void validateAgainstBlueprint(
                Map<String, Object> characterData,
                CharacterBlueprintDto blueprint,
                ValidateCharacterResponse response) {

            if (blueprint.getActorFields() == null) return;

            for (Object fieldObj : blueprint.getActorFields()) {
                if (!(fieldObj instanceof Map)) continue;
                Map<String, Object> field = (Map<String, Object>) fieldObj;

                String path = (String) field.get("path");
                String type = (String) field.get("type");
                Boolean required = (Boolean) field.get("required");

                if (path == null) continue;

                Object value = resolveNestedValue(characterData, path);

                if (Boolean.TRUE.equals(required) && (value == null || value.toString().isBlank())) {
                    response.getErrors().add(new ValidateCharacterResponse.ValidationError(
                            path, "Required field is empty", "error"));
                    continue;
                }

                if (value == null) continue;

                if ("number".equals(type) && value instanceof Number numVal) {
                    Object minObj = field.get("min");
                    Object maxObj = field.get("max");

                    if (minObj instanceof Number min && numVal.doubleValue() < min.doubleValue()) {
                        response.getErrors().add(new ValidateCharacterResponse.ValidationError(
                                path, String.format("Value %s is below minimum %s", numVal, min), "error"));
                    }
                    if (maxObj instanceof Number max && numVal.doubleValue() > max.doubleValue()) {
                        response.getErrors().add(new ValidateCharacterResponse.ValidationError(
                                path, String.format("Value %s exceeds maximum %s", numVal, max), "error"));
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        private Object resolveNestedValue(Map<String, Object> data, String path) {
            String cleanPath = path.startsWith("system.") ? path.substring(7) : path;
            String[] parts = cleanPath.split("\\.");
            Object current = data;
            for (String part : parts) {
                if (current instanceof Map) {
                    current = ((Map<String, Object>) current).get(part);
                } else {
                    return null;
                }
            }
            return current;
        }
    }
}
