package dev.agiro.masterserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.ReferenceCharacterDto;
import dev.agiro.masterserver.dto.SystemProfileDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds dynamic, system-aware prompts for character/item generation
 * using the System Knowledge Profile instead of hardcoded field names.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemAwarePromptBuilder {

    private final ObjectMapper objectMapper;

    /**
     * Build the system context block to inject into any generation prompt.
     * This replaces the hardcoded "actorGuidance" with profile-aware context.
     */
    public String buildSystemContext(SystemProfileDto profile) {
        StringBuilder ctx = new StringBuilder();

        ctx.append("=== GAME SYSTEM: ").append(profile.getSystemTitle())
                .append(" (").append(profile.getSystemId()).append(") ===\n\n");

        if (profile.getSystemSummary() != null) {
            ctx.append("System Description: ").append(profile.getSystemSummary()).append("\n\n");
        }

        // Character creation steps
        if (profile.getCharacterCreationSteps() != null && !profile.getCharacterCreationSteps().isEmpty()) {
            ctx.append("CHARACTER CREATION STEPS:\n");
            for (int i = 0; i < profile.getCharacterCreationSteps().size(); i++) {
                ctx.append(i + 1).append(". ").append(profile.getCharacterCreationSteps().get(i)).append("\n");
            }
            ctx.append("\n");
        }

        // Available choices
        if (profile.getCreationChoices() != null && !profile.getCreationChoices().isEmpty()) {
            ctx.append("AVAILABLE CHOICES:\n");
            for (var entry : profile.getCreationChoices().entrySet()) {
                ctx.append("- ").append(entry.getKey()).append(": ")
                        .append(String.join(", ", entry.getValue())).append("\n");
            }
            ctx.append("\n");
        }

        // Constraints
        if (profile.getDetectedConstraints() != null && !profile.getDetectedConstraints().isEmpty()) {
            ctx.append("CONSTRAINTS:\n");
            for (var constraint : profile.getDetectedConstraints()) {
                ctx.append("- ").append(constraint.getDescription()).append("\n");
            }
            ctx.append("\n");
        }

        // Value ranges
        if (profile.getValueRanges() != null && !profile.getValueRanges().isEmpty()) {
            ctx.append("TYPICAL VALUE RANGES:\n");
            for (var entry : profile.getValueRanges().entrySet()) {
                var range = entry.getValue();
                ctx.append("- ").append(entry.getKey())
                        .append(": min=").append(range.getMin())
                        .append(", max=").append(range.getMax())
                        .append(", typical=").append(range.getTypical()).append("\n");
            }
            ctx.append("\n");
        }

        return ctx.toString();
    }

    /**
     * Build a dynamic core concept system prompt that adapts to ANY system.
     * Replaces the hardcoded CORE_CONCEPT_SYSTEM_PROMPT.
     */
    public String buildCoreConceptPrompt(SystemProfileDto profile, String language) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an expert character creator for tabletop RPG systems.\n\n");

        prompt.append("Create a core concept for a character in the ").append(profile.getSystemTitle()).append(" system.\n\n");

        // Dynamically determine which identity fields to generate
        List<String> identityFields = profile.getIdentityFields();
        if (identityFields == null || identityFields.isEmpty()) {
            identityFields = List.of("name", "concept", "biography", "description");
        }

        prompt.append("Generate the following identity fields:\n");
        for (String field : identityFields) {
            String fieldName = field.contains(".") ? field.substring(field.lastIndexOf('.') + 1) : field;
            prompt.append("- \"").append(fieldName).append("\"\n");
        }

        prompt.append("\nAlso always include:\n");
        prompt.append("- \"name\": The character's name\n");
        prompt.append("- \"concept\": Brief concept (2-3 sentences)\n");
        prompt.append("- \"description\": Physical/personality description\n\n");

        prompt.append("Respond ONLY with valid JSON containing these fields.\n");
        prompt.append("Language: ").append(language).append("\n");
        prompt.append("Be creative and evocative.\n");

        return prompt.toString();
    }

    /**
     * Build the field-filling system prompt that adapts to ANY system.
     * Replaces the hardcoded FILL_FIELDS_SYSTEM_PROMPT.
     */
    public String buildFillFieldsPrompt(SystemProfileDto profile, String language) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an expert at filling character sheet fields for the ")
                .append(profile.getSystemTitle()).append(" RPG system.\n\n");

        prompt.append("You will receive:\n");
        prompt.append("1. A character concept\n");
        prompt.append("2. A list of fields to fill\n");
        prompt.append("3. System rules and guidance\n\n");

        prompt.append("CRITICAL RULES:\n");
        prompt.append("- Fill EVERY field in the list\n");
        prompt.append("- Respect field types: \"string\" for text, \"number\" for integers, \"resource\" for numbers\n");
        prompt.append("- Respect min/max values strictly for each field\n");

        // Add system-specific constraints with explicit mathematical instructions
        if (profile.getDetectedConstraints() != null) {
            boolean hasBudgetConstraints = false;
            for (var constraint : profile.getDetectedConstraints()) {
                if ("point_budget".equals(constraint.getType())) {
                    hasBudgetConstraints = true;
                    prompt.append("\n*** POINT BUDGET CONSTRAINT (MANDATORY) ***\n");
                    prompt.append("- ").append(constraint.getDescription()).append("\n");
                    if (constraint.getParameters() != null) {
                        Object total = constraint.getParameters().get("total");
                        Object min = constraint.getParameters().get("min");
                        Object max = constraint.getParameters().get("max");
                        if (total != null) {
                            prompt.append("- The SUM of all numeric values under '")
                                    .append(constraint.getFieldPath())
                                    .append("' MUST equal EXACTLY ").append(total).append("\n");
                            prompt.append("- Before responding, ADD UP all values for this group and verify the total = ")
                                    .append(total).append("\n");
                        }
                        if (min != null) {
                            prompt.append("- Each individual field value must be >= ").append(min).append("\n");
                        }
                        if (max != null) {
                            prompt.append("- Each individual field value must be <= ").append(max).append("\n");
                        }
                    }
                    prompt.append("*** END POINT BUDGET CONSTRAINT ***\n\n");
                } else {
                    prompt.append("- CONSTRAINT: ").append(constraint.getDescription()).append("\n");
                }
            }
            if (hasBudgetConstraints) {
                prompt.append("IMPORTANT: Double-check your arithmetic before responding. ");
                prompt.append("Sum all numeric values for budget-constrained field groups and ensure they match the required total.\n\n");
            }
        }

        // Add value range guidance
        if (profile.getValueRanges() != null && !profile.getValueRanges().isEmpty()) {
            prompt.append("- Use typical value ranges from this system (see provided ranges)\n");
        }

        prompt.append("- Use the character concept to inform your choices\n\n");

        prompt.append("RESPONSE FORMAT:\n");
        prompt.append("Respond ONLY with valid JSON - a flat object with field paths as keys:\n");
        prompt.append("{\n  \"system.field.path\": value,\n  ...\n}\n\n");
        prompt.append("Language: ").append(language).append("\n");

        return prompt.toString();
    }

    /**
     * Build the item generation system prompt that adapts to ANY system.
     * Replaces the hardcoded GENERATE_ITEMS_SYSTEM_PROMPT.
     */
    public String buildItemGenerationPrompt(SystemProfileDto profile, String language) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an expert at creating items for the ")
                .append(profile.getSystemTitle()).append(" RPG system.\n\n");

        prompt.append("Based on the character concept and available item types, create 2-4 relevant items.\n\n");

        // Add constraints about items if available
        if (profile.getValueRanges() != null) {
            var itemRanges = profile.getValueRanges().entrySet().stream()
                    .filter(e -> e.getKey().contains("item") || e.getKey().contains("damage") ||
                            e.getKey().contains("weight") || e.getKey().contains("cost"))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            if (!itemRanges.isEmpty()) {
                prompt.append("ITEM VALUE RANGES:\n");
                for (var entry : itemRanges.entrySet()) {
                    prompt.append("- ").append(entry.getKey())
                            .append(": ").append(entry.getValue().getMin())
                            .append("-").append(entry.getValue().getMax()).append("\n");
                }
                prompt.append("\n");
            }
        }

        prompt.append("Respond ONLY with valid JSON array.\n");
        prompt.append("Language: ").append(language).append("\n");

        return prompt.toString();
    }

    /**
     * Get field groups from the profile for dynamic field grouping.
     * This replaces the hardcoded groupFields() in CharacterGenerationService.
     */
    public Map<String, List<String>> getFieldGroupsFromProfile(SystemProfileDto profile) {
        Map<String, List<String>> groups = new LinkedHashMap<>();

        if (profile.getFieldGroups() == null) return groups;

        // Order: identity first, then attributes, skills, combat, resources, other
        List<String> categoryOrder = List.of("identity", "attributes", "skills", "combat", "resources", "magic", "equipment", "other");

        List<SystemProfileDto.FieldGroup> sorted = new ArrayList<>(profile.getFieldGroups());
        sorted.sort(Comparator.comparingInt(g -> {
            int idx = categoryOrder.indexOf(g.getCategory());
            return idx >= 0 ? idx : categoryOrder.size();
        }));

        for (var group : sorted) {
            groups.put(group.getName(), group.getFieldPaths());
        }

        return groups;
    }

    // ── Reference Character Context ─────────────────────────────────────

    /**
     * Build a context block showing the reference character's data structure.
     * This is the single most impactful prompt enhancement: the AI sees a REAL
     * character and can replicate its exact structure.
     *
     * @param ref      The stored reference character
     * @param section  Which part to show: "system" (actor.system data), "items", or "all"
     * @return prompt context block
     */
    public String buildReferenceCharacterContext(ReferenceCharacterDto ref, String section) {
        if (ref == null) return "";

        StringBuilder ctx = new StringBuilder();
        ctx.append("\n=== REFERENCE CHARACTER (").append(ref.getLabel()).append(") ===\n");
        ctx.append("This is a real, manually-created character. Your output MUST match this exact structure.\n\n");

        try {
            if ("system".equals(section) || "all".equals(section)) {
                // Extract only actor.system data (the part AI fills)
                Map<String, Object> actorData = ref.getActorData();
                if (actorData != null) {
                    Object systemData = actorData.get("system");
                    if (systemData != null) {
                        String systemJson = objectMapper.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(systemData);
                        // Truncate if massive
                        if (systemJson.length() > 6000) {
                            systemJson = systemJson.substring(0, 6000) + "\n... (truncated)";
                        }
                        ctx.append("REFERENCE actor.system data (your field values must use the same paths and value types):\n");
                        ctx.append(systemJson).append("\n\n");
                    }
                }
            }

            if ("items".equals(section) || "all".equals(section)) {
                List<Map<String, Object>> items = ref.getItems();
                if (items != null && !items.isEmpty()) {
                    ctx.append("REFERENCE embedded items (your generated items MUST use the same structure):\n");
                    // Show up to 3 items as examples, one per distinct type
                    Set<String> seenTypes = new HashSet<>();
                    int shown = 0;
                    for (Map<String, Object> item : items) {
                        String type = (String) item.get("type");
                        if (type != null && seenTypes.contains(type)) continue;
                        if (type != null) seenTypes.add(type);
                        
                        String itemJson = objectMapper.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(item);
                        if (itemJson.length() > 2000) {
                            itemJson = itemJson.substring(0, 2000) + "\n... (truncated)";
                        }
                        ctx.append("Item example (type=").append(type).append("):\n");
                        ctx.append(itemJson).append("\n\n");
                        
                        if (++shown >= 5) break; // limit to 5 distinct types
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to serialize reference character context: {}", e.getMessage());
            ctx.append("(Failed to serialize reference data)\n");
        }

        ctx.append("=== END REFERENCE CHARACTER ===\n\n");
        return ctx.toString();
    }

    /**
     * Build a prompt section that explicitly mandates character-definition items (CDIs).
     * <p>
     * In systems like Hitos, skills (habilidad), traits (rasgo) and powers (poder) are
     * embedded Foundry items — not actor fields and not optional equipment. They MUST
     * be generated for every character of that type.
     * <p>
     * This method groups reference items by type, counts them, and builds a prompt block
     * that tells the AI exactly how many items of each type to produce and what the
     * field structure looks like.
     *
     * @param ref           The stored reference character
     * @param requiredTypes The item types extracted from the reference (all unique types)
     * @return prompt context block with mandatory item instructions
     */
    public String buildRequiredItemTypesContext(ReferenceCharacterDto ref, List<String> requiredTypes) {
        if (ref == null || ref.getItems() == null || requiredTypes.isEmpty()) return "";

        // Group reference items by type (preserve insertion order)
        Map<String, List<Map<String, Object>>> itemsByType = new LinkedHashMap<>();
        for (Map<String, Object> item : ref.getItems()) {
            String type = (String) item.get("type");
            if (type != null && requiredTypes.contains(type)) {
                itemsByType.computeIfAbsent(type, k -> new ArrayList<>()).add(item);
            }
        }

        StringBuilder ctx = new StringBuilder();
        ctx.append("=== REQUIRED CHARACTER ITEMS (from reference character '")
                .append(ref.getLabel()).append("') ===\n");
        ctx.append("These item types are MANDATORY for every character of this type in this system.\n");
        ctx.append("You MUST generate ALL items listed below — do NOT skip any type.\n\n");

        int totalRequired = 0;
        for (String type : requiredTypes) {
            List<Map<String, Object>> examples = itemsByType.getOrDefault(type, List.of());
            int count = examples.isEmpty() ? 1 : examples.size();
            totalRequired += count;

            ctx.append("Type \"").append(type).append("\" — generate EXACTLY ").append(count)
                    .append(" item(s)\n");

            if (!examples.isEmpty()) {
                ctx.append("  Structural example (use same field names and value types):\n");
                try {
                    String json = objectMapper.writeValueAsString(examples.get(0));
                    if (json.length() > 1200) json = json.substring(0, 1200) + "... (truncated)";
                    ctx.append("  ").append(json.replace("\n", "\n  ")).append("\n");
                } catch (Exception e) {
                    log.warn("Failed to serialize reference item for type '{}': {}", type, e.getMessage());
                }
            }
            ctx.append("\n");
        }

        ctx.append("TOTAL items to generate for mandatory types: ").append(totalRequired).append("\n");
        ctx.append("=== END REQUIRED CHARACTER ITEMS ===\n\n");
        return ctx.toString();
    }

    /**
     * Build a compact structural summary of the reference character's items.
     * Shows available item types and their field structure without all the values.
     */
    public String buildReferenceItemStructureSummary(ReferenceCharacterDto ref) {
        if (ref == null || ref.getItems() == null || ref.getItems().isEmpty()) return "";

        StringBuilder ctx = new StringBuilder();
        ctx.append("REFERENCE ITEM STRUCTURES (your items must match these formats exactly):\n");

        Map<String, Map<String, Object>> typeExamples = new LinkedHashMap<>();
        for (Map<String, Object> item : ref.getItems()) {
            String type = (String) item.get("type");
            if (type != null && !typeExamples.containsKey(type)) {
                typeExamples.put(type, item);
            }
        }

        try {
            for (var entry : typeExamples.entrySet()) {
                ctx.append("\nType \"").append(entry.getKey()).append("\":\n");
                String json = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(entry.getValue());
                if (json.length() > 1500) json = json.substring(0, 1500) + "...";
                ctx.append(json).append("\n");
            }
        } catch (Exception e) {
            log.warn("Failed to build item structure summary: {}", e.getMessage());
        }

        return ctx.toString();
    }
}

