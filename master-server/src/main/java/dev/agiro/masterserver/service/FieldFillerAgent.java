package dev.agiro.masterserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.CharacterBlueprintDto;
import dev.agiro.masterserver.dto.CreateCharacterRequest;
import dev.agiro.masterserver.dto.SystemProfileDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Specialized agent for filling mechanical fields in groups and enforcing
 * numeric constraints. Used by CharacterGenerationService as a helper.
 */
@Slf4j
@Service
public class FieldFillerAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final SystemProfileService systemProfileService;
    private final SystemAwarePromptBuilder promptBuilder;
    private final GameMasterManualSolver gameMasterManualSolver;

    private static final String FALLBACK_FILL_FIELDS_PROMPT = """
            You are an expert at filling character sheet fields for tabletop RPG systems.
            
            You will receive:
            1. A character concept
            2. A list of fields to fill
            3. System rules and guidance
            
            CRITICAL RULES:
            - Fill EVERY field in the list
            - Respect field types: "string" for text, "number" for integers, "resource" for numbers
            - Respect min/max constraints strictly
            - If a POINT BUDGET is specified, the values for the constrained fields MUST sum to EXACTLY the required total
            - Before responding, verify the arithmetic: add up all numeric values for budget-constrained groups
            - Distribute values wisely based on the character concept
            - Use the character concept to inform your choices
            
            RESPONSE FORMAT: valid JSON - a flat object with field paths as keys.
            Language: {language}
            """;

    public FieldFillerAgent(ChatClient.Builder chatClientBuilder,
                            ObjectMapper objectMapper,
                            SystemProfileService systemProfileService,
                            SystemAwarePromptBuilder promptBuilder,
                            GameMasterManualSolver gameMasterManualSolver) {
        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.8)
                        .build())
                .build();
        this.objectMapper = objectMapper;
        this.systemProfileService = systemProfileService;
        this.promptBuilder = promptBuilder;
        this.gameMasterManualSolver = gameMasterManualSolver;
    }

    /**
     * Fill all blueprint fields in semantic groups and then enforce numeric
     * constraints. Optionally reports per-group progress via callback.
     */
    public Map<String, Object> fillFieldsInGroups(
            Map<String, Object> coreConcept,
            CreateCharacterRequest request,
            String language,
            BiConsumer<String, Integer> progressCallback) throws Exception {

        Map<String, Object> allSystemData = new java.util.HashMap<>();
        String systemId = request.getBlueprint().getSystemId();
        SystemProfileDto profile = resolveProfile(systemId);

        // Build system context once
        String systemContext;
        if (profile != null) {
            systemContext = promptBuilder.buildSystemContext(profile);
        } else {
            systemContext = getActorTypeGuidance(request.getActorType(), systemId);
        }

        // Group fields: profile-based dynamic grouping or legacy fallback
        Map<String, List<CharacterBlueprintDto.FieldDto>> fieldGroups;
        if (profile != null && profile.getFieldGroups() != null && !profile.getFieldGroups().isEmpty()) {
            fieldGroups = groupFieldsFromProfile(profile, request.getBlueprint());
            log.info("Using profile-based field grouping: {} groups", fieldGroups.size());
        } else {
            fieldGroups = groupFieldsLegacy(request.getBlueprint());
            log.info("Using legacy field grouping: {} groups", fieldGroups.size());
        }

        int groupIndex = 0;
        int totalGroups = fieldGroups.size();

        for (Map.Entry<String, List<CharacterBlueprintDto.FieldDto>> entry : fieldGroups.entrySet()) {
            String groupName = entry.getKey();
            List<CharacterBlueprintDto.FieldDto> fields = entry.getValue();

            int progress = 40 + (40 * groupIndex / Math.max(totalGroups, 1));
            if (progressCallback != null) {
                progressCallback.accept("Filling " + groupName + "...", progress);
            }

            log.info("Filling field group '{}' with {} fields", groupName, fields.size());

            Map<String, Object> groupValues = fillFieldGroup(coreConcept, fields, systemContext, language, request);
            allSystemData.putAll(groupValues);

            groupIndex++;
        }

        return enforceConstraints(allSystemData, request, profile);
    }

    public Map<String, Object> enforceConstraints(Map<String, Object> systemData,
                                                  CreateCharacterRequest request,
                                                  SystemProfileDto profile) {
        if (profile == null || profile.getDetectedConstraints() == null) {
            return systemData;
        }

        Map<String, Object> corrected = new java.util.HashMap<>(systemData);

        // Point budget constraints
        for (var constraint : profile.getDetectedConstraints()) {
            if (!"point_budget".equals(constraint.getType())) continue;
            if (constraint.getParameters() == null) continue;

            Number totalBudgetNum = (Number) constraint.getParameters().get("total");
            if (totalBudgetNum == null) continue;

            double totalBudget = totalBudgetNum.doubleValue();
            String budgetPath = constraint.getFieldPath();
            Number minValNum = (Number) constraint.getParameters().get("min");
            Number maxValNum = (Number) constraint.getParameters().get("max");
            double minVal = minValNum != null ? minValNum.doubleValue() : 0;
            double maxVal = maxValNum != null ? maxValNum.doubleValue() : Double.MAX_VALUE;

            List<String> budgetFieldKeys = corrected.keySet().stream()
                    .filter(k -> k.startsWith(budgetPath))
                    .filter(k -> corrected.get(k) instanceof Number)
                    .collect(Collectors.toList());

            if (budgetFieldKeys.isEmpty()) continue;

            double currentTotal = budgetFieldKeys.stream()
                    .mapToDouble(k -> ((Number) corrected.get(k)).doubleValue())
                    .sum();

            if (Math.abs(currentTotal - totalBudget) < 0.01) {
                log.info("Budget constraint '{}' already satisfied: total={}", budgetPath, currentTotal);
                continue;
            }

            log.info("Enforcing budget constraint '{}': AI total={}, required={}, fields={}",
                    budgetPath, currentTotal, totalBudget, budgetFieldKeys.size());

            if (currentTotal > 0) {
                double scale = totalBudget / currentTotal;
                double runningTotal = 0;

                for (int i = 0; i < budgetFieldKeys.size(); i++) {
                    String key = budgetFieldKeys.get(i);
                    double original = ((Number) corrected.get(key)).doubleValue();

                    if (i == budgetFieldKeys.size() - 1) {
                        double remaining = totalBudget - runningTotal;
                        double clamped = Math.max(minVal, Math.min(maxVal, remaining));
                        corrected.put(key, (int) Math.round(clamped));
                    } else {
                        double scaled = original * scale;
                        double clamped = Math.max(minVal, Math.min(maxVal, scaled));
                        int rounded = (int) Math.round(clamped);
                        corrected.put(key, rounded);
                        runningTotal += rounded;
                    }
                }

                double finalTotal = budgetFieldKeys.stream()
                        .mapToDouble(k -> ((Number) corrected.get(k)).doubleValue())
                        .sum();

                if (Math.abs(finalTotal - totalBudget) > 0.01) {
                    int diff = (int) Math.round(totalBudget - finalTotal);
                    for (String key : budgetFieldKeys) {
                        if (diff == 0) break;
                        int current = ((Number) corrected.get(key)).intValue();
                        if (diff > 0 && current < maxVal) {
                            int add = (int) Math.min(diff, maxVal - current);
                            corrected.put(key, current + add);
                            diff -= add;
                        } else if (diff < 0 && current > minVal) {
                            int sub = (int) Math.min(-diff, current - minVal);
                            corrected.put(key, current - sub);
                            diff += sub;
                        }
                    }
                }
            } else {
                int perField = (int) Math.round(totalBudget / budgetFieldKeys.size());
                double runningTotal = 0;
                for (int i = 0; i < budgetFieldKeys.size(); i++) {
                    if (i == budgetFieldKeys.size() - 1) {
                        int remaining = (int) Math.round(totalBudget - runningTotal);
                        corrected.put(budgetFieldKeys.get(i), (int) Math.max(minVal, Math.min(maxVal, remaining)));
                    } else {
                        int clamped = (int) Math.max(minVal, Math.min(maxVal, perField));
                        corrected.put(budgetFieldKeys.get(i), clamped);
                        runningTotal += clamped;
                    }
                }
            }
        }

        // Range constraints
        for (var constraint : profile.getDetectedConstraints()) {
            if (!"range".equals(constraint.getType())) continue;
            if (constraint.getParameters() == null) continue;

            String path = constraint.getFieldPath();
            Object value = corrected.get(path);
            if (!(value instanceof Number)) continue;

            double numVal = ((Number) value).doubleValue();
            Number minNum = (Number) constraint.getParameters().get("min");
            Number maxNum = (Number) constraint.getParameters().get("max");

            double min = minNum != null ? minNum.doubleValue() : Double.MIN_VALUE;
            double max = maxNum != null ? maxNum.doubleValue() : Double.MAX_VALUE;

            if (numVal < min || numVal > max) {
                int clamped = (int) Math.round(Math.max(min, Math.min(max, numVal)));
                corrected.put(path, clamped);
                log.info("Range constraint enforced for '{}': {} → {} (min={}, max={})", path, numVal, clamped, min, max);
            }
        }

        return corrected;
    }

    private Map<String, Object> fillFieldGroup(
            Map<String, Object> coreConcept,
            List<CharacterBlueprintDto.FieldDto> fields,
            String systemContext,
            String language,
            CreateCharacterRequest request) throws Exception {

        SystemProfileDto profile = resolveProfile(request.getBlueprint().getSystemId());

        var refOpt = systemProfileService.getReferenceCharacter(
                request.getBlueprint().getSystemId(), request.getActorType());

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Character Concept:\n");
        userPrompt.append(objectMapper.writeValueAsString(coreConcept)).append("\n\n");

        userPrompt.append("System Rules & Context:\n");
        userPrompt.append(systemContext).append("\n\n");

        if (refOpt.isPresent()) {
            userPrompt.append(promptBuilder.buildReferenceCharacterContext(refOpt.get(), "system"));
        }

        userPrompt.append("Fields to fill:\n");
        userPrompt.append(objectMapper.writeValueAsString(fields)).append("\n\n");

        if (profile != null && profile.getValueRanges() != null && !profile.getValueRanges().isEmpty()) {
            userPrompt.append("Typical value ranges for reference:\n");
            for (CharacterBlueprintDto.FieldDto field : fields) {
                SystemProfileDto.ValueRange range = profile.getValueRanges().get(field.getPath());
                if (range != null) {
                    userPrompt.append("  ").append(field.getPath())
                            .append(": min=").append(range.getMin())
                            .append(", max=").append(range.getMax())
                            .append(", typical=").append(range.getTypical()).append("\n");
                }
            }
            userPrompt.append("\n");
        }

        if (profile != null && profile.getDetectedConstraints() != null) {
            for (var constraint : profile.getDetectedConstraints()) {
                if (!"point_budget".equals(constraint.getType())) continue;

                String budgetPath = constraint.getFieldPath();
                List<CharacterBlueprintDto.FieldDto> budgetFields = fields.stream()
                        .filter(f -> f.getPath() != null && f.getPath().startsWith(budgetPath))
                        .filter(f -> "number".equals(f.getType()) || "resource".equals(f.getType()))
                        .collect(Collectors.toList());

                if (!budgetFields.isEmpty() && constraint.getParameters() != null) {
                    Number totalBudget = (Number) constraint.getParameters().get("total");
                    Number minVal = (Number) constraint.getParameters().get("min");
                    Number maxVal = (Number) constraint.getParameters().get("max");

                    if (totalBudget != null) {
                        userPrompt.append("\n=== MANDATORY BUDGET FOR THIS GROUP ===\n");
                        userPrompt.append("The following ").append(budgetFields.size())
                                .append(" fields must have their values SUM to EXACTLY ")
                                .append(totalBudget).append(":\n");
                        for (var bf : budgetFields) {
                            userPrompt.append("  - ").append(bf.getPath()).append("\n");
                        }
                        if (minVal != null) {
                            userPrompt.append("Each value must be >= ").append(minVal).append("\n");
                        }
                        if (maxVal != null) {
                            userPrompt.append("Each value must be <= ").append(maxVal).append("\n");
                        }
                        userPrompt.append("VERIFY: After choosing values, confirm the sum = ")
                                .append(totalBudget).append(" before responding.\n");
                        userPrompt.append("=== END BUDGET ===\n\n");
                    }
                }
            }
        }

        userPrompt.append("Fill ALL these fields with appropriate values based on the character concept.");

        String systemPrompt = (profile != null)
                ? promptBuilder.buildFillFieldsPrompt(profile, language)
                : FALLBACK_FILL_FIELDS_PROMPT.replace("{language}", language);

        String responseJson = chatClient.prompt()
                .system(systemPrompt)
                .user(u -> u.text("{userPrompt}").param("userPrompt", userPrompt.toString()))
                .call()
                .content();

        responseJson = cleanJsonResponse(responseJson);
        log.debug("Field group response: {}", responseJson);

        return objectMapper.readValue(responseJson, Map.class);
    }

    private SystemProfileDto resolveProfile(String systemId) {
        return systemProfileService.getProfile(systemId).orElse(null);
    }

    private String getActorTypeGuidance(String actorType, String systemId) {
        try {
            return gameMasterManualSolver.solveDoubt(
                    String.format("How do I create a %s in this game system? What are the rules and important considerations?", actorType),
                    systemId
            );
        } catch (Exception e) {
            log.warn("Failed to retrieve actor type guidance", e);
            return "";
        }
    }

    private Map<String, List<CharacterBlueprintDto.FieldDto>> groupFieldsFromProfile(
            SystemProfileDto profile, CharacterBlueprintDto blueprint) {

        Map<String, List<String>> profileGroups = promptBuilder.getFieldGroupsFromProfile(profile);
        Map<String, List<CharacterBlueprintDto.FieldDto>> result = new java.util.LinkedHashMap<>();
        Set<String> assignedPaths = new java.util.HashSet<>();

        Map<String, CharacterBlueprintDto.FieldDto> fieldMap = new java.util.LinkedHashMap<>();
        for (Object fieldObj : blueprint.getActorFields()) {
            CharacterBlueprintDto.FieldDto field = objectMapper.convertValue(fieldObj, CharacterBlueprintDto.FieldDto.class);
            fieldMap.put(field.getPath(), field);
        }

        for (Map.Entry<String, List<String>> groupEntry : profileGroups.entrySet()) {
            String groupName = groupEntry.getKey();
            List<CharacterBlueprintDto.FieldDto> groupFields = new java.util.ArrayList<>();

            for (String profilePath : groupEntry.getValue()) {
                if (fieldMap.containsKey(profilePath) && !assignedPaths.contains(profilePath)) {
                    groupFields.add(fieldMap.get(profilePath));
                    assignedPaths.add(profilePath);
                    continue;
                }
                for (Map.Entry<String, CharacterBlueprintDto.FieldDto> fe : fieldMap.entrySet()) {
                    if (fe.getKey().startsWith(profilePath) && !assignedPaths.contains(fe.getKey())) {
                        groupFields.add(fe.getValue());
                        assignedPaths.add(fe.getKey());
                    }
                }
            }

            if (!groupFields.isEmpty()) {
                result.put(groupName, groupFields);
            }
        }

        List<CharacterBlueprintDto.FieldDto> unassigned = new java.util.ArrayList<>();
        for (Map.Entry<String, CharacterBlueprintDto.FieldDto> fe : fieldMap.entrySet()) {
            if (!assignedPaths.contains(fe.getKey())) {
                unassigned.add(fe.getValue());
            }
        }
        if (!unassigned.isEmpty()) {
            result.put("other fields", unassigned);
        }

        return result;
    }

    private Map<String, List<CharacterBlueprintDto.FieldDto>> groupFieldsLegacy(CharacterBlueprintDto blueprint) {
        Map<String, List<CharacterBlueprintDto.FieldDto>> groups = new java.util.LinkedHashMap<>();

        for (Object fieldObj : blueprint.getActorFields()) {
            CharacterBlueprintDto.FieldDto field = objectMapper.convertValue(fieldObj, CharacterBlueprintDto.FieldDto.class);
            String path = field.getPath();

            String[] parts = path.split("\\.");
            String groupKey;
            if (parts.length >= 3 && "system".equals(parts[0])) {
                groupKey = parts[1];
            } else if (parts.length >= 2) {
                groupKey = parts[0];
            } else {
                groupKey = "other";
            }

            String groupName = groupKey.substring(0, 1).toUpperCase() + groupKey.substring(1)
                    .replaceAll("([A-Z])", " $1").replaceAll("_", " ").trim();

            groups.computeIfAbsent(groupName, k -> new java.util.ArrayList<>()).add(field);
        }

        return groups;
    }

    private String cleanJsonResponse(String response) {
        if (response == null) return "{}";
        response = response.trim();
        if (response.startsWith("```json")) {
            response = response.substring(7);
        } else if (response.startsWith("```")) {
            response = response.substring(3);
        }
        if (response.endsWith("```")) {
            response = response.substring(0, response.length() - 3);
        }
        return response.trim();
    }
}
