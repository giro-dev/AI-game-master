package dev.agiro.masterserver.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.SemanticMapDto;
import dev.agiro.masterserver.dto.SemanticMapDto.FieldMapping;
import dev.agiro.masterserver.dto.SemanticMapDto.SemanticConcept;
import dev.agiro.masterserver.dto.SystemProfileDto;
import dev.agiro.masterserver.dto.SystemSnapshotDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Semantic Field Inference Service — maps game-system-specific field paths to
 * universal RPG concepts (health, level, stat_strength, …).
 * <p>
 * Uses a two-pass approach:
 * <ol>
 *   <li>Heuristic keyword matching (fast, zero LLM cost) — covers ~70 % of popular systems.</li>
 *   <li>LLM structured-output pass for fields that remain ambiguous after the heuristic pass.</li>
 * </ol>
 * The resulting {@link SemanticMapDto} enables system-agnostic character generation.
 */
@Slf4j
@Service
public class SemanticFieldInferenceService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    private static final String LLM_INFERENCE_PROMPT = """
            You are mapping Foundry VTT game-system fields to universal RPG concepts.
            
            UNIVERSAL CONCEPTS: %s
            
            KNOWN MAPPING EXAMPLE (dnd5e):
              "system.attributes.hp.value" → HEALTH
              "system.abilities.str.value" → STAT_STRENGTH
              "system.details.level"       → LEVEL
            
            UNKNOWN FIELDS with sampled values:
            %s
            
            Map each field to the closest universal concept.
            Respond ONLY as valid JSON: { "fieldPath": "CONCEPT_NAME" }
            Do NOT include markdown or explanation.
            """;

    public SemanticFieldInferenceService(ChatClient.Builder chatClientBuilder,
                                          ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder().model("gpt-4.1-mini").temperature(0.0).build())
                .build();
        this.objectMapper = objectMapper;
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Build a {@link SemanticMapDto} for the given snapshot data.
     * First applies heuristics, then uses the LLM for ambiguous fields.
     */
    public SemanticMapDto inferSemanticMap(
            SystemSnapshotDto.SchemaData schemas,
            SystemSnapshotDto.CompendiumSamples samples,
            Map<String, Map<String, SystemSnapshotDto.ValueDistribution>> valueDistributions) {

        List<String> fieldPaths = collectFieldPaths(schemas);
        if (fieldPaths.isEmpty()) return new SemanticMapDto();

        // Step 1: heuristic pass
        Map<String, SemanticConcept> mapped = new LinkedHashMap<>();
        List<String> ambiguous = new ArrayList<>();

        for (String path : fieldPaths) {
            SemanticConcept concept = heuristicMatch(path);
            if (concept != SemanticConcept.UNKNOWN) {
                mapped.put(path, concept);
            } else {
                ambiguous.add(path);
            }
        }

        log.debug("Heuristic pass: {} mapped, {} ambiguous out of {} total",
                mapped.size(), ambiguous.size(), fieldPaths.size());

        // Step 2: LLM pass for ambiguous fields (cap at 30 to manage cost)
        if (!ambiguous.isEmpty()) {
            List<String> subset = ambiguous.subList(0, Math.min(30, ambiguous.size()));
            Map<String, SemanticConcept> llmMapped = llmInference(subset, valueDistributions);
            mapped.putAll(llmMapped);
        }

        return buildSemanticMap(mapped);
    }

    /**
     * Infer point-budget constraints from value distributions and field paths.
     * If a group of numeric fields under the same parent all have similar averages,
     * we assume they share a point pool.
     */
    public List<SystemProfileDto.DetectedConstraint> inferPointBudgetConstraints(
            List<String> fieldPaths,
            Map<String, Map<String, SystemSnapshotDto.ValueDistribution>> valueDistributions,
            List<SystemProfileDto.DetectedConstraint> existingConstraints) {

        List<SystemProfileDto.DetectedConstraint> inferred = new ArrayList<>();
        if (valueDistributions == null || valueDistributions.isEmpty()) return inferred;

        // Group fields by their parent prefix (e.g. "system.abilities")
        Map<String, List<String>> byParent = new LinkedHashMap<>();
        for (String path : fieldPaths) {
            String[] parts = path.split("\\.");
            if (parts.length >= 4) {
                String parent = parts[0] + "." + parts[1] + "." + parts[2];
                byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(path);
            }
        }

        Set<String> existingPaths = new HashSet<>();
        if (existingConstraints != null) {
            existingConstraints.forEach(c -> existingPaths.add(c.getFieldPath()));
        }

        for (var entry : byParent.entrySet()) {
            String parent = entry.getKey();
            List<String> children = entry.getValue();
            if (children.size() < 3) continue; // Only interesting for groups of 3+
            if (existingPaths.contains(parent)) continue;

            // Detect point-budget: collect avg values
            List<Double> avgs = new ArrayList<>();
            for (var distEntry : valueDistributions.entrySet()) {
                var fieldDist = distEntry.getValue();
                for (String child : children) {
                    if (fieldDist.containsKey(child)) {
                        var dist = fieldDist.get(child);
                        if (dist.getAvg() != null) avgs.add(dist.getAvg().doubleValue());
                    }
                }
            }

            if (avgs.size() >= 3) {
                double total = avgs.stream().mapToDouble(Double::doubleValue).sum();
                if (total > 0) {
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("total", Math.round(total));
                    params.put("fieldCount", children.size());
                    inferred.add(SystemProfileDto.DetectedConstraint.builder()
                            .type("point_budget")
                            .fieldPath(parent)
                            .description("Inferred point budget for " + parent + " fields")
                            .parameters(params)
                            .build());
                }
            }
        }

        return inferred;
    }

    /**
     * Compute an overall confidence score (0.0–1.0) for the semantic map.
     * Based on ratio of mapped fields to total fields.
     */
    public double computeOverallConfidence(SemanticMapDto semanticMap, int totalFieldCount) {
        if (semanticMap == null || totalFieldCount == 0) return 0.0;

        int mappedCount = 0;
        if (semanticMap.getHealth() != null) mappedCount++;
        if (semanticMap.getLevel() != null) mappedCount++;
        if (semanticMap.getExperience() != null) mappedCount++;
        if (semanticMap.getArmorClass() != null) mappedCount++;
        if (semanticMap.getInitiative() != null) mappedCount++;
        if (semanticMap.getPrimaryStats() != null) mappedCount += semanticMap.getPrimaryStats().size();
        if (semanticMap.getSkills() != null) mappedCount += Math.min(semanticMap.getSkills().size(), 5);

        return Math.min(1.0, (double) mappedCount / Math.max(1, Math.min(totalFieldCount, 20)));
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private SemanticConcept heuristicMatch(String path) {
        // Use simple string operations (no regex) to avoid ReDoS on untrusted field path input.
        // Field paths are dot-separated tokens, e.g. "system.attributes.hp.value".
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        List<String> segments = List.of(lower.split("\\."));

        // Health (hp / health / vida / currentphysical ending with "value")
        if (lower.endsWith(".value") &&
                segmentContainsAny(segments, "hp", "health", "vida", "currentphysical")) {
            return SemanticConcept.HEALTH;
        }
        // Secondary health (stun / currentsun / sanidad ending with "value")
        if (lower.endsWith(".value") &&
                segmentContainsAny(segments, "stun", "currentsun", "sanidad")) {
            return SemanticConcept.HEALTH_SECONDARY;
        }
        // Level
        if (segmentEndsWithAny(lower, "level", "nivel", "rang")) {
            return SemanticConcept.LEVEL;
        }
        // Experience
        if (containsAny(lower, "xp", "experience", "experiencia")) {
            return SemanticConcept.EXPERIENCE;
        }
        // Primary stats (match intermediate segment, e.g. ".str.")
        if (segmentContainsAny(segments, "str", "fuerza", "forza")) {
            return SemanticConcept.STAT_STRENGTH;
        }
        if (segmentContainsAny(segments, "dex", "destreza", "destrezza", "agilidad", "agilidade")) {
            return SemanticConcept.STAT_DEXTERITY;
        }
        if (segmentContainsAny(segments, "con", "constitution", "constitución")) {
            return SemanticConcept.STAT_CONSTITUTION;
        }
        if (segmentContainsAny(segments, "int", "intelligence", "inteligencia")) {
            return SemanticConcept.STAT_INTELLIGENCE;
        }
        if (segmentContainsAny(segments, "wis", "wisdom", "sabiduria", "intuición")) {
            return SemanticConcept.STAT_WISDOM;
        }
        if (segmentContainsAny(segments, "cha", "charisma", "carisma")) {
            return SemanticConcept.STAT_CHARISMA;
        }
        // Skills (segment named "skills", "habilidades", etc. AND ends with "rank", "value", or "mod")
        if (segmentContainsAny(segments, "skills", "skill", "habilidades", "fertigkeiten") &&
                (lower.endsWith(".rank") || lower.endsWith(".value") || lower.endsWith(".mod"))) {
            return SemanticConcept.SKILL_RANK;
        }
        // Armor class
        if (segmentContainsAny(segments, "ac", "armor", "armadura")) {
            return SemanticConcept.ARMOR_CLASS;
        }
        // Initiative
        if (containsAny(lower, "initiative", "iniciativa", ".ini")) {
            return SemanticConcept.INITIATIVE;
        }
        // Movement
        if (containsAny(lower, "speed", "movement", ".mov", "movimiento")) {
            return SemanticConcept.MOVEMENT_SPEED;
        }
        // Currency
        if (containsAny(lower, "currency", "gold", "money", "dinero", "nuyen", "credits")) {
            return SemanticConcept.CURRENCY;
        }
        // Biography / narrative
        if (containsAny(lower, ".bio", "biography", ".desc", "trasfondo", "biographie")) {
            return SemanticConcept.BIOGRAPHY;
        }
        // Spell slots
        if (containsAny(lower, "spellslot", "spell.slot", "slots")) {
            return SemanticConcept.SPELL_SLOTS;
        }
        // Proficiency
        if (containsAny(lower, ".prof", "proficiency", "maîtrise")) {
            return SemanticConcept.PROFICIENCY;
        }

        return SemanticConcept.UNKNOWN;
    }

    /** True if the lowercased path contains ANY of the given literal substrings. */
    private static boolean containsAny(String lower, String... keywords) {
        for (String kw : keywords) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    /** True if any path segment exactly equals one of the given keywords. */
    private static boolean segmentContainsAny(List<String> segments, String... keywords) {
        for (String seg : segments) {
            for (String kw : keywords) {
                if (seg.equals(kw)) return true;
            }
        }
        return false;
    }

    /** True if the lowercased path ends with ".<keyword>" for any of the given keywords. */
    private static boolean segmentEndsWithAny(String lower, String... keywords) {
        for (String kw : keywords) {
            if (lower.endsWith("." + kw) || lower.equals(kw)) return true;
        }
        return false;
    }

    private Map<String, SemanticConcept> llmInference(
            List<String> ambiguousFields,
            Map<String, Map<String, SystemSnapshotDto.ValueDistribution>> valueDistributions) {

        try {
            StringBuilder fieldsBlock = new StringBuilder();
            for (String path : ambiguousFields) {
                fieldsBlock.append("  \"").append(path).append("\"");
                // Add sample values if available
                List<Double> samples = getSampleValues(path, valueDistributions);
                if (!samples.isEmpty()) {
                    fieldsBlock.append(" → samples: ").append(samples);
                }
                fieldsBlock.append("\n");
            }

            String conceptList = Arrays.stream(SemanticConcept.values())
                    .map(Enum::name)
                    .collect(java.util.stream.Collectors.joining(", "));

            String prompt = LLM_INFERENCE_PROMPT.formatted(conceptList, fieldsBlock);

            String raw = chatClient.prompt()
                    .user(u -> u.text("{p}").param("p", prompt))
                    .call().content();

            Map<String, String> result = objectMapper.readValue(cleanJson(raw), new TypeReference<>() {});
            Map<String, SemanticConcept> mapped = new LinkedHashMap<>();
            result.forEach((path, conceptName) -> {
                try {
                    mapped.put(path, SemanticConcept.valueOf(conceptName.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    mapped.put(path, SemanticConcept.UNKNOWN);
                }
            });
            return mapped;

        } catch (Exception e) {
            log.debug("LLM semantic inference failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private List<Double> getSampleValues(String path,
                                          Map<String, Map<String, SystemSnapshotDto.ValueDistribution>> dist) {
        if (dist == null) return List.of();
        for (var entry : dist.entrySet()) {
            if (entry.getValue().containsKey(path)) {
                var d = entry.getValue().get(path);
                List<Double> values = new ArrayList<>();
                if (d.getMin() != null) values.add(d.getMin().doubleValue());
                if (d.getAvg() != null) values.add(d.getAvg().doubleValue());
                if (d.getMax() != null) values.add(d.getMax().doubleValue());
                return values;
            }
        }
        return List.of();
    }

    private SemanticMapDto buildSemanticMap(Map<String, SemanticConcept> mapped) {
        SemanticMapDto dto = new SemanticMapDto();
        List<FieldMapping> primaryStats = new ArrayList<>();
        List<FieldMapping> skills = new ArrayList<>();

        for (var entry : mapped.entrySet()) {
            String path = entry.getKey();
            SemanticConcept concept = entry.getValue();
            FieldMapping fm = FieldMapping.builder()
                    .path(path).type("number").required(false)
                    .inferredAs(concept).confidence(0.8).build();

            switch (concept) {
                case HEALTH -> dto.setHealth(fm);
                case HEALTH_SECONDARY -> dto.setHealthSecondary(fm);
                case LEVEL -> dto.setLevel(fm);
                case EXPERIENCE -> dto.setExperience(fm);
                case ARMOR_CLASS -> dto.setArmorClass(fm);
                case INITIATIVE -> dto.setInitiative(fm);
                case MOVEMENT_SPEED -> dto.setMovementSpeed(fm);
                case STAT_STRENGTH, STAT_DEXTERITY, STAT_CONSTITUTION,
                     STAT_INTELLIGENCE, STAT_WISDOM, STAT_CHARISMA,
                     STAT_GENERIC -> primaryStats.add(fm);
                case SKILL_RANK -> skills.add(fm);
                default -> { /* UNKNOWN, CURRENCY, etc. — skip for now */ }
            }
        }

        if (!primaryStats.isEmpty()) dto.setPrimaryStats(primaryStats);
        if (!skills.isEmpty()) dto.setSkills(skills);
        return dto;
    }

    private List<String> collectFieldPaths(SystemSnapshotDto.SchemaData schemas) {
        List<String> paths = new ArrayList<>();
        if (schemas == null || schemas.getActors() == null) return paths;
        for (var entry : schemas.getActors().entrySet()) {
            var schemaEntry = entry.getValue();
            if (schemaEntry.getFields() != null) {
                schemaEntry.getFields().forEach(f -> {
                    Object p = f.get("path");
                    if (p instanceof String s) paths.add(s);
                });
            }
        }
        return paths;
    }

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String t = raw.trim();
        if (t.startsWith("```json")) t = t.substring(7);
        else if (t.startsWith("```")) t = t.substring(3);
        if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        return t.trim();
    }
}
