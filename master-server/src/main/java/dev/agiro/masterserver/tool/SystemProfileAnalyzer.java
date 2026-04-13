package dev.agiro.masterserver.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.RollMechanicsDto;
import dev.agiro.masterserver.dto.SemanticMapDto;
import dev.agiro.masterserver.dto.SystemProfileDto;
import dev.agiro.masterserver.dto.SystemSnapshotDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes system snapshots and builds System Knowledge Profiles.
 * Contains the AI-powered analysis, field grouping, and semantic inference logic
 * extracted from the old monolithic SystemProfileService.
 */
@Slf4j
@Service
public class SystemProfileAnalyzer {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final RAGService ragService;
    private final SemanticFieldInferenceService semanticInference;

    private static final String FIELD_GROUPING_PROMPT = """
            You are an expert at understanding tabletop RPG character sheet structures.
            
            I will give you a list of field paths from a game system's character sheet schema,
            along with their types and labels. Your job is to group these fields into semantic categories.
            
            Rules:
            - Group related fields together (e.g., all ability scores, all skills, all combat stats)
            - Each group should have a descriptive name and a category
            - Categories MUST be one of: "identity", "attributes", "skills", "combat", "resources", "magic", "equipment", "other"
            - Every field must belong to exactly one group
            - Use the field paths and labels to infer meaning
            - If sample data is provided, use it to understand the field's purpose
            
            Respond with a JSON array of groups:
            [
              {
                "name": "Group Name",
                "description": "What these fields represent",
                "category": "attributes",
                "fieldPaths": ["system.abilities.str.value", "system.abilities.dex.value", ...]
              }
            ]
            
            Respond ONLY with valid JSON. No markdown.
            """;

    private static final String SYSTEM_ANALYSIS_PROMPT = """
            You are an expert at analyzing tabletop RPG game systems.
            
            I will give you:
            1. A system schema (field definitions)
            2. Sample characters/items from the system
            3. Rules context from ingested manuals (if available)
            
            Analyze this system and provide:
            1. A brief summary of what kind of RPG system this is (2-3 sentences)
            2. The character creation steps (ordered list)
            3. Which fields are identity/narrative fields (name, bio, description)
            4. Which fields are mechanical/combat fields
            5. Any detected constraints (point budgets, valid ranges, required fields)
            6. Available creation choices (races, classes, archetypes) if detectable
            
            Respond with JSON:
            {
              "systemSummary": "...",
              "characterCreationSteps": ["Step 1: ...", "Step 2: ..."],
              "identityFields": ["system.biography", ...],
              "mechanicalFields": ["system.abilities.str", ...],
              "detectedConstraints": [
                {
                  "type": "point_budget|range|required|enum",
                  "fieldPath": "system.abilities",
                  "description": "...",
                  "parameters": { "min": 0, "max": 10, "total": 18 }
                }
              ],
              "creationChoices": {
                "race": ["Human", "Elf", ...],
                "class": ["Fighter", "Mage", ...]
              }
            }
            
            Respond ONLY with valid JSON. No markdown.
            """;

    public SystemProfileAnalyzer(ChatClient.Builder chatClientBuilder,
                                 ObjectMapper objectMapper,
                                 RAGService ragService,
                                 SemanticFieldInferenceService semanticInference) {
        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder()
                        .model("gpt-4.1-mini")
                        .temperature(0.2)
                        .build())
                .build();
        this.objectMapper = objectMapper;
        this.ragService = ragService;
        this.semanticInference = semanticInference;
    }

    /**
     * Build a full SystemProfileDto from a snapshot.
     */
    public SystemProfileDto analyze(SystemSnapshotDto snapshot) {
        // Step 1: Build field groups from schema
        List<SystemProfileDto.FieldGroup> fieldGroups = buildFieldGroups(snapshot);

        // Step 2: AI-driven system analysis
        SystemAnalysis analysis = analyzeSystem(snapshot);

        // Step 3: Value ranges from distributions
        Map<String, SystemProfileDto.ValueRange> valueRanges = buildValueRanges(snapshot);

        // Step 4: Heuristic semantic field mappings
        SemanticMapDto semanticMap = semanticInference.inferSemanticMap(
                snapshot.getSchemas(), snapshot.getCompendiumSamples(), snapshot.getValueDistributions());

        // Step 4b: Deterministic point-budget constraints
        List<String> allFieldPaths = collectAllFieldPaths(snapshot);
        List<SystemProfileDto.DetectedConstraint> inferredBudgets =
                semanticInference.inferPointBudgetConstraints(
                        allFieldPaths, snapshot.getValueDistributions(), analysis.constraints);

        List<SystemProfileDto.DetectedConstraint> mergedConstraints = mergeConstraints(analysis.constraints, inferredBudgets);

        // Step 5: Roll mechanics
        RollMechanicsDto rollMechanics = buildRollMechanics(snapshot);

        // Step 6: Confidence
        double confidence = semanticInference.computeOverallConfidence(semanticMap, allFieldPaths.size());

        // Step 7: Examples
        List<Map<String, Object>> actorExamples = extractExamples(snapshot.getCompendiumSamples(), true);
        List<Map<String, Object>> itemExamples = extractExamples(snapshot.getCompendiumSamples(), false);

        // Assemble
        SystemProfileDto profile = SystemProfileDto.builder()
                .systemId(snapshot.getSystemId())
                .systemVersion(snapshot.getSystemVersion())
                .systemTitle(snapshot.getSystemTitle())
                .lastUpdated(Instant.now().toEpochMilli())
                .fieldGroups(fieldGroups)
                .detectedConstraints(mergedConstraints)
                .characterCreationSteps(analysis.creationSteps)
                .creationChoices(analysis.creationChoices)
                .identityFields(analysis.identityFields)
                .mechanicalFields(analysis.mechanicalFields)
                .valueRanges(valueRanges)
                .systemSummary(analysis.summary)
                .enrichedFromManuals(false)
                .semanticMap(semanticMap)
                .rollMechanics(rollMechanics)
                .confidence(confidence)
                .actorExamples(actorExamples)
                .itemExamples(itemExamples)
                .build();

        log.info("Profile analyzed for {}: {} groups, {} constraints ({} inferred)",
                snapshot.getSystemId(), fieldGroups.size(), mergedConstraints.size(), inferredBudgets.size());

        return profile;
    }

    /**
     * Enrich an existing profile with knowledge from ingested manuals.
     */
    public void enrichFromManuals(SystemProfileDto profile, String systemId) {
        try {
            String creationRules = ragService.searchCharacterCreationContext(
                    "How to create a character step by step", systemId, 5);

            if (!creationRules.isEmpty() &&
                    (profile.getCharacterCreationSteps() == null || profile.getCharacterCreationSteps().isEmpty())) {
                String rulesText = truncate(creationRules, 3000);
                String stepsJson = chatClient.prompt()
                        .system("Extract character creation steps from the following rules text. " +
                                "Return a JSON array of strings, each being one step. Respond ONLY with JSON.")
                        .user(u -> u.text("{userPrompt}").param("userPrompt", rulesText))
                        .call()
                        .content();
                List<String> steps = objectMapper.readValue(cleanJson(stepsJson), new TypeReference<>() {});
                profile.setCharacterCreationSteps(steps);
            }

            String choicesContext = ragService.searchExtractedEntities(
                    "races classes archetypes available for character creation",
                    systemId, null, null, 10);

            if (!choicesContext.isEmpty() &&
                    (profile.getCreationChoices() == null || profile.getCreationChoices().isEmpty())) {
                String choicesText = truncate(choicesContext, 3000);
                String choicesJson = chatClient.prompt()
                        .system("Extract available character creation choices from the text. " +
                                "Return JSON: {\"race\": [...], \"class\": [...], \"archetype\": [...]}. " +
                                "Only include categories that exist. Respond ONLY with JSON.")
                        .user(u -> u.text("{userPrompt}").param("userPrompt", choicesText))
                        .call()
                        .content();
                Map<String, List<String>> choices = objectMapper.readValue(cleanJson(choicesJson), new TypeReference<>() {});
                profile.setCreationChoices(choices);
            }

            profile.setEnrichedFromManuals(true);
        } catch (Exception e) {
            log.debug("Manual enrichment failed: {}", e.getMessage());
        }
    }

    /**
     * Build a minimal fallback profile when analysis fails.
     */
    public SystemProfileDto buildFallbackProfile(SystemSnapshotDto snapshot) {
        List<SystemProfileDto.FieldGroup> groups = new ArrayList<>();
        if (snapshot.getSchemas() != null && snapshot.getSchemas().getActors() != null) {
            for (var entry : snapshot.getSchemas().getActors().entrySet()) {
                groups.addAll(structuralGrouping(entry.getValue().getFields()));
            }
        }
        return SystemProfileDto.builder()
                .systemId(snapshot.getSystemId())
                .systemVersion(snapshot.getSystemVersion())
                .systemTitle(snapshot.getSystemTitle())
                .lastUpdated(Instant.now().toEpochMilli())
                .fieldGroups(groups)
                .detectedConstraints(List.of())
                .characterCreationSteps(List.of())
                .creationChoices(Map.of())
                .identityFields(List.of())
                .mechanicalFields(List.of())
                .valueRanges(Map.of())
                .systemSummary(snapshot.getSystemTitle() + " RPG system")
                .enrichedFromManuals(false)
                .build();
    }

    // ─── Private helpers ────────────────────────────────────────────────

    private List<SystemProfileDto.DetectedConstraint> mergeConstraints(
            List<SystemProfileDto.DetectedConstraint> aiConstraints,
            List<SystemProfileDto.DetectedConstraint> inferred) {
        List<SystemProfileDto.DetectedConstraint> merged = new ArrayList<>(aiConstraints);
        if (inferred.isEmpty()) return merged;

        Set<String> existingPaths = aiConstraints.stream()
                .filter(c -> "point_budget".equals(c.getType()))
                .map(SystemProfileDto.DetectedConstraint::getFieldPath)
                .collect(Collectors.toSet());

        for (var c : inferred) {
            if (!existingPaths.contains(c.getFieldPath())) {
                merged.add(c);
                log.info("Added inferred point-budget: {} → total={}",
                        c.getFieldPath(), c.getParameters().get("total"));
            }
        }
        return merged;
    }

    private List<SystemProfileDto.FieldGroup> buildFieldGroups(SystemSnapshotDto snapshot) {
        if (snapshot.getSchemas() == null || snapshot.getSchemas().getActors() == null) return List.of();

        List<SystemProfileDto.FieldGroup> allGroups = new ArrayList<>();
        for (var entry : snapshot.getSchemas().getActors().entrySet()) {
            String actorType = entry.getKey();
            var schemaEntry = entry.getValue();
            if (schemaEntry.getFields() == null || schemaEntry.getFields().isEmpty()) continue;

            try {
                var structuralGroups = structuralGrouping(schemaEntry.getFields());
                if (structuralGroups.size() >= 3) {
                    allGroups.addAll(structuralGroups);
                } else {
                    allGroups.addAll(aiFieldGrouping(schemaEntry.getFields(), snapshot));
                }
            } catch (Exception e) {
                log.warn("Field grouping failed for {}: {}", actorType, e.getMessage());
                allGroups.add(SystemProfileDto.FieldGroup.builder()
                        .name(actorType + " fields").description("All fields for " + actorType)
                        .category("other")
                        .fieldPaths(schemaEntry.getFields().stream()
                                .map(f -> (String) f.get("path")).filter(Objects::nonNull).toList())
                        .build());
            }
        }
        return allGroups;
    }

    List<SystemProfileDto.FieldGroup> structuralGrouping(List<Map<String, Object>> fields) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            String path = (String) field.get("path");
            if (path == null) continue;
            String[] parts = path.split("\\.");
            String groupKey = (parts.length >= 3 && "system".equals(parts[0])) ? parts[1]
                    : (parts.length >= 2) ? parts[0] : "other";
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(path);
        }
        return groups.entrySet().stream()
                .map(e -> SystemProfileDto.FieldGroup.builder()
                        .name(prettifyGroupName(e.getKey()))
                        .description("Fields under " + e.getKey())
                        .category(inferCategory(e.getKey()))
                        .fieldPaths(e.getValue()).build())
                .toList();
    }

    private List<SystemProfileDto.FieldGroup> aiFieldGrouping(
            List<Map<String, Object>> fields, SystemSnapshotDto snapshot) throws Exception {
        StringBuilder prompt = new StringBuilder();
        prompt.append("System: ").append(snapshot.getSystemTitle()).append(" (").append(snapshot.getSystemId()).append(")\n\n");
        prompt.append("Fields to group:\n").append(objectMapper.writeValueAsString(fields)).append("\n\n");
        if (snapshot.getCompendiumSamples() != null && snapshot.getCompendiumSamples().getActors() != null) {
            for (var s : snapshot.getCompendiumSamples().getActors().entrySet()) {
                if (!s.getValue().isEmpty()) {
                    prompt.append("Sample ").append(s.getKey()).append(":\n")
                            .append(objectMapper.writeValueAsString(s.getValue().getFirst())).append("\n\n");
                    break;
                }
            }
        }
        String json = chatClient.prompt()
                .system(FIELD_GROUPING_PROMPT)
                .user(u -> u.text("{userPrompt}").param("userPrompt", prompt.toString()))
                .call().content();
        return objectMapper.readValue(cleanJson(json), new TypeReference<>() {});
    }

    private SystemAnalysis analyzeSystem(SystemSnapshotDto snapshot) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("System: ").append(snapshot.getSystemTitle()).append(" (").append(snapshot.getSystemId()).append(")\n\n");
            if (snapshot.getSchemas() != null) {
                prompt.append("Actor Types: ").append(snapshot.getSchemas().getActorTypes()).append("\n");
                prompt.append("Item Types: ").append(snapshot.getSchemas().getItemTypes()).append("\n\n");
                for (var e : snapshot.getSchemas().getActors().entrySet()) {
                    prompt.append("Schema for '").append(e.getKey()).append("':\n");
                    prompt.append(truncate(objectMapper.writeValueAsString(e.getValue().getFields()), 4000)).append("\n\n");
                    break;
                }
            }
            if (snapshot.getCompendiumSamples() != null && snapshot.getCompendiumSamples().getActors() != null) {
                for (var e : snapshot.getCompendiumSamples().getActors().entrySet()) {
                    if (!e.getValue().isEmpty()) {
                        prompt.append("Sample ").append(e.getKey()).append(" characters:\n");
                        for (int i = 0; i < Math.min(2, e.getValue().size()); i++) {
                            prompt.append(truncate(objectMapper.writeValueAsString(e.getValue().get(i)), 2000)).append("\n");
                        }
                        prompt.append("\n");
                    }
                }
            }
            if (snapshot.getValueDistributions() != null && !snapshot.getValueDistributions().isEmpty()) {
                prompt.append("Value distributions:\n")
                        .append(objectMapper.writeValueAsString(snapshot.getValueDistributions())).append("\n\n");
            }
            try {
                String rag = ragService.searchCharacterCreationContext("character creation rules and steps", snapshot.getSystemId(), 5);
                if (!rag.isEmpty()) prompt.append("Rules from manuals:\n").append(truncate(rag, 3000)).append("\n\n");
            } catch (Exception e) { log.debug("RAG unavailable: {}", e.getMessage()); }

            prompt.append("Analyze this RPG system.");
            String json = cleanJson(chatClient.prompt()
                    .system(SYSTEM_ANALYSIS_PROMPT)
                    .user(u -> u.text("{userPrompt}").param("userPrompt", prompt.toString()))
                    .call().content());

            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            SystemAnalysis a = new SystemAnalysis();
            a.summary = (String) map.getOrDefault("systemSummary", "Unknown RPG system");
            a.creationSteps = map.containsKey("characterCreationSteps") ? objectMapper.convertValue(map.get("characterCreationSteps"), new TypeReference<>() {}) : List.of();
            a.identityFields = map.containsKey("identityFields") ? objectMapper.convertValue(map.get("identityFields"), new TypeReference<>() {}) : List.of();
            a.mechanicalFields = map.containsKey("mechanicalFields") ? objectMapper.convertValue(map.get("mechanicalFields"), new TypeReference<>() {}) : List.of();
            a.constraints = map.containsKey("detectedConstraints") ? objectMapper.convertValue(map.get("detectedConstraints"), new TypeReference<>() {}) : List.of();
            a.creationChoices = map.containsKey("creationChoices") ? objectMapper.convertValue(map.get("creationChoices"), new TypeReference<>() {}) : Map.of();
            return a;
        } catch (Exception e) {
            log.warn("System analysis failed: {}", e.getMessage());
            SystemAnalysis f = new SystemAnalysis();
            f.summary = snapshot.getSystemTitle() + " RPG system";
            f.creationSteps = List.of(); f.identityFields = List.of(); f.mechanicalFields = List.of();
            f.constraints = List.of(); f.creationChoices = Map.of();
            return f;
        }
    }

    private Map<String, SystemProfileDto.ValueRange> buildValueRanges(SystemSnapshotDto snapshot) {
        Map<String, SystemProfileDto.ValueRange> ranges = new HashMap<>();
        if (snapshot.getValueDistributions() == null) return ranges;
        for (var te : snapshot.getValueDistributions().entrySet()) {
            for (var fe : te.getValue().entrySet()) {
                var d = fe.getValue();
                ranges.put(fe.getKey(), SystemProfileDto.ValueRange.builder()
                        .min(d.getMin()).max(d.getMax()).typical(d.getAvg()).fieldType("number").build());
            }
        }
        return ranges;
    }

    private RollMechanicsDto buildRollMechanics(SystemSnapshotDto snapshot) {
        if (snapshot.getRollMechanics() == null) return null;
        var rm = snapshot.getRollMechanics();
        RollMechanicsDto.SuccessModel sm;
        try { sm = RollMechanicsDto.SuccessModel.valueOf(rm.getSuccessModel() != null ? rm.getSuccessModel().toUpperCase() : "UNKNOWN"); }
        catch (IllegalArgumentException e) { sm = RollMechanicsDto.SuccessModel.UNKNOWN; }

        List<RollMechanicsDto.RollTriggerField> triggers = new ArrayList<>();
        if (rm.getRollTriggerFields() != null) {
            rm.getRollTriggerFields().forEach(tf -> triggers.add(
                    RollMechanicsDto.RollTriggerField.builder().path(tf.getPath()).type(tf.getType()).context(tf.getContext()).build()));
        }
        return RollMechanicsDto.builder()
                .formula(rm.getDiceFormulas() != null && !rm.getDiceFormulas().isEmpty() ? rm.getDiceFormulas().getFirst() : null)
                .successModel(sm).skillAsItem(rm.isSkillAsItem()).diceFormulas(rm.getDiceFormulas()).rollTriggerFields(triggers).build();
    }

    List<String> collectAllFieldPaths(SystemSnapshotDto snapshot) {
        List<String> paths = new ArrayList<>();
        if (snapshot.getSchemas() == null || snapshot.getSchemas().getActors() == null) return paths;
        for (var e : snapshot.getSchemas().getActors().entrySet()) {
            if (e.getValue().getFields() != null) {
                e.getValue().getFields().forEach(f -> { String p = (String) f.get("path"); if (p != null) paths.add(p); });
            }
        }
        return paths;
    }

    private List<Map<String, Object>> extractExamples(SystemSnapshotDto.CompendiumSamples samples, boolean actors) {
        if (samples == null) return List.of();
        var source = actors ? samples.getActors() : samples.getItems();
        if (source == null || source.isEmpty()) return List.of();
        List<Map<String, Object>> examples = new ArrayList<>();
        for (var e : source.entrySet()) {
            for (var s : e.getValue()) { if (examples.size() >= 3) break; examples.add(s); }
            if (examples.size() >= 3) break;
        }
        return examples;
    }

    String prettifyGroupName(String key) {
        return key.substring(0, 1).toUpperCase() + key.substring(1)
                .replaceAll("([A-Z])", " $1").replaceAll("_", " ").trim();
    }

    String inferCategory(String groupKey) {
        String l = groupKey.toLowerCase();
        if (l.contains("abilit") || l.contains("atribut") || l.contains("attr")) return "attributes";
        if (l.contains("skill") || l.contains("habilidad") || l.contains("proficien")) return "skills";
        if (l.contains("combat") || l.contains("defens") || l.contains("attack")) return "combat";
        if (l.contains("hp") || l.contains("health") || l.contains("resource") || l.contains("aguante")) return "resources";
        if (l.contains("spell") || l.contains("magic") || l.contains("magia")) return "magic";
        if (l.contains("bio") || l.contains("concept") || l.contains("descript") || l.contains("persona")) return "identity";
        return "other";
    }

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String t = raw.trim();
        if (t.startsWith("```json")) t = t.substring(7);
        else if (t.startsWith("```")) t = t.substring(3);
        if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        return t.trim();
    }

    private String truncate(String s, int max) { return s.length() > max ? s.substring(0, max) + "..." : s; }

    static class SystemAnalysis {
        String summary;
        List<String> creationSteps;
        List<String> identityFields;
        List<String> mechanicalFields;
        List<SystemProfileDto.DetectedConstraint> constraints;
        Map<String, List<String>> creationChoices;
    }
}

