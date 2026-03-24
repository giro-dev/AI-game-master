package dev.agiro.masterserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
}

