package dev.agiro.masterserver.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.ReferenceCharacterDto;
import dev.agiro.masterserver.dto.SystemProfileDto;
import dev.agiro.masterserver.dto.SystemSnapshotDto;
import dev.agiro.masterserver.repository.ReferenceCharacterRepository;
import dev.agiro.masterserver.repository.SystemProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and maintains System Knowledge Profiles — the AI's "learned" understanding
 * of any Foundry VTT game system.
 * <p>
 * Combines runtime schema introspection (from snapshots) with ingested manual knowledge
 * (from RAG) to create a comprehensive system profile that drives dynamic prompts
 * and field grouping.
 */
@Slf4j
@Service
public class SystemProfileService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final RAGService ragService;
    private final ReferenceCharacterRepository referenceCharacterRepository;
    private final SystemProfileRepository systemProfileRepository;
    private final Map<String, SystemProfileDto> profileCache = new ConcurrentHashMap<>();

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

    public SystemProfileService(ChatClient.Builder chatClientBuilder,
                                ObjectMapper objectMapper,
                                RAGService ragService,
                                ReferenceCharacterRepository referenceCharacterRepository,
                                SystemProfileRepository systemProfileRepository) {
        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder()
                        .model("gpt-4.1-mini")
                        .temperature(0.2)
                        .build())
                .build();
        this.objectMapper = objectMapper;
        this.ragService = ragService;
        this.referenceCharacterRepository = referenceCharacterRepository;
        this.systemProfileRepository = systemProfileRepository;
    }

    /**
     * Process a system snapshot and build/update the System Knowledge Profile.
     */
    public SystemProfileDto processSnapshot(SystemSnapshotDto snapshot) {
        log.info("Processing system snapshot for: {} v{}", snapshot.getSystemId(), snapshot.getSystemVersion());

        try {
            // Check if we have a cached or persisted profile that's still valid
            SystemProfileDto existing = getProfile(snapshot.getSystemId()).orElse(null);
            if (existing != null && snapshot.getSystemVersion().equals(existing.getSystemVersion())) {
                log.info("Profile already up to date for {} v{}", snapshot.getSystemId(), snapshot.getSystemVersion());
                return existing;
            }

            // Step 1: Build field groups from schema using AI
            List<SystemProfileDto.FieldGroup> fieldGroups = buildFieldGroups(snapshot);

            // Step 2: Analyze the system using schema + samples + RAG context
            SystemAnalysis analysis = analyzeSystem(snapshot);

            // Step 3: Extract value ranges from distributions + samples
            Map<String, SystemProfileDto.ValueRange> valueRanges = buildValueRanges(snapshot);

            // Step 4: Assemble the profile
            SystemProfileDto profile = SystemProfileDto.builder()
                    .systemId(snapshot.getSystemId())
                    .systemVersion(snapshot.getSystemVersion())
                    .systemTitle(snapshot.getSystemTitle())
                    .lastUpdated(Instant.now().toEpochMilli())
                    .fieldGroups(fieldGroups)
                    .detectedConstraints(analysis.constraints)
                    .characterCreationSteps(analysis.creationSteps)
                    .creationChoices(analysis.creationChoices)
                    .identityFields(analysis.identityFields)
                    .mechanicalFields(analysis.mechanicalFields)
                    .valueRanges(valueRanges)
                    .systemSummary(analysis.summary)
                    .enrichedFromManuals(false)
                    .build();

            // Try to enrich from ingested manuals
            try {
                enrichFromManuals(profile, snapshot.getSystemId());
            } catch (Exception e) {
                log.warn("Manual enrichment failed (non-blocking): {}", e.getMessage());
            }

            profileCache.put(snapshot.getSystemId(), profile);
            systemProfileRepository.save(profile);
            log.info("System profile built for {}: {} field groups, {} constraints",
                    snapshot.getSystemId(), fieldGroups.size(), analysis.constraints.size());

            return profile;

        } catch (Exception e) {
            log.error("Failed to process snapshot for {}", snapshot.getSystemId(), e);
            // Return a minimal fallback profile
            return buildFallbackProfile(snapshot);
        }
    }

    /**
     * Get an existing profile by system ID.
     */
    public Optional<SystemProfileDto> getProfile(String systemId) {
        SystemProfileDto cached = profileCache.get(systemId);
        if (cached != null) {
            return Optional.of(cached);
        }

        Optional<SystemProfileDto> stored = systemProfileRepository.find(systemId);
        stored.ifPresent(p -> profileCache.put(systemId, p));
        return stored;
    }

    /**
     * Enrich a profile with knowledge from ingested manuals.
     * Called after ingestion pipeline completes.
     */
    public void enrichFromIngestion(String systemId, List<Document> classifiedChunks, List<Document> entityDocs) {
        SystemProfileDto profile = getProfile(systemId).orElse(null);
        if (profile == null) {
            log.warn("No profile found for system {} to enrich", systemId);
            return;
        }

        try {
            enrichFromManuals(profile, systemId);
            profile.setEnrichedFromManuals(true);
            profile.setLastUpdated(Instant.now().toEpochMilli());
            profileCache.put(systemId, profile);
            systemProfileRepository.save(profile);
            log.info("Profile enriched from ingestion for system {}", systemId);
        } catch (Exception e) {
            log.warn("Failed to enrich profile from ingestion: {}", e.getMessage());
        }
    }

    // ─── AI-Powered Field Grouping ──────────────────────────────────────

    private List<SystemProfileDto.FieldGroup> buildFieldGroups(SystemSnapshotDto snapshot) {
        if (snapshot.getSchemas() == null || snapshot.getSchemas().getActors() == null) {
            return List.of();
        }

        List<SystemProfileDto.FieldGroup> allGroups = new ArrayList<>();

        for (Map.Entry<String, SystemSnapshotDto.SchemaEntry> entry : snapshot.getSchemas().getActors().entrySet()) {
            String actorType = entry.getKey();
            SystemSnapshotDto.SchemaEntry schemaEntry = entry.getValue();

            if (schemaEntry.getFields() == null || schemaEntry.getFields().isEmpty()) continue;

            try {
                // First try structural grouping (fast, no AI call)
                List<SystemProfileDto.FieldGroup> structuralGroups = structuralGrouping(schemaEntry.getFields());

                // If structural grouping produced reasonable groups, use it
                if (structuralGroups.size() >= 3) {
                    allGroups.addAll(structuralGroups);
                    continue;
                }

                // Otherwise, use AI to group fields
                List<SystemProfileDto.FieldGroup> aiGroups = aiFieldGrouping(schemaEntry.getFields(), snapshot);
                allGroups.addAll(aiGroups);

            } catch (Exception e) {
                log.warn("Field grouping failed for actor type {}: {}", actorType, e.getMessage());
                // Fallback: put all fields in one group
                allGroups.add(SystemProfileDto.FieldGroup.builder()
                        .name(actorType + " fields")
                        .description("All fields for " + actorType)
                        .category("other")
                        .fieldPaths(schemaEntry.getFields().stream()
                                .map(f -> (String) f.get("path"))
                                .filter(Objects::nonNull)
                                .toList())
                        .build());
            }
        }

        return allGroups;
    }

    /**
     * Group fields by their path hierarchy (fast, no AI needed).
     */
    private List<SystemProfileDto.FieldGroup> structuralGrouping(List<Map<String, Object>> fields) {
        Map<String, List<String>> groups = new LinkedHashMap<>();

        for (Map<String, Object> field : fields) {
            String path = (String) field.get("path");
            if (path == null) continue;

            // Extract the top-level group from the path
            // e.g., "system.abilities.str.value" → "abilities"
            String[] parts = path.split("\\.");
            String groupKey;
            if (parts.length >= 3 && "system".equals(parts[0])) {
                groupKey = parts[1]; // "abilities", "skills", "attributes", etc.
            } else if (parts.length >= 2) {
                groupKey = parts[0];
            } else {
                groupKey = "other";
            }

            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(path);
        }

        return groups.entrySet().stream()
                .map(entry -> SystemProfileDto.FieldGroup.builder()
                        .name(prettifyGroupName(entry.getKey()))
                        .description("Fields under " + entry.getKey())
                        .category(inferCategory(entry.getKey()))
                        .fieldPaths(entry.getValue())
                        .build())
                .toList();
    }

    /**
     * Use AI to semantically group fields when structural grouping is insufficient.
     */
    private List<SystemProfileDto.FieldGroup> aiFieldGrouping(
            List<Map<String, Object>> fields, SystemSnapshotDto snapshot) throws Exception {

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("System: ").append(snapshot.getSystemTitle())
                .append(" (").append(snapshot.getSystemId()).append(")\n\n");
        userPrompt.append("Fields to group:\n");
        userPrompt.append(objectMapper.writeValueAsString(fields)).append("\n\n");

        // Add sample data if available
        if (snapshot.getCompendiumSamples() != null && snapshot.getCompendiumSamples().getActors() != null) {
            for (var sampleEntry : snapshot.getCompendiumSamples().getActors().entrySet()) {
                if (!sampleEntry.getValue().isEmpty()) {
                    userPrompt.append("Sample ").append(sampleEntry.getKey()).append(":\n");
                    userPrompt.append(objectMapper.writeValueAsString(sampleEntry.getValue().get(0))).append("\n\n");
                    break; // One sample is enough for grouping context
                }
            }
        }

        String responseJson = chatClient.prompt()
                .system(FIELD_GROUPING_PROMPT)
                .user(u -> u.text("{userPrompt}").param("userPrompt", userPrompt.toString()))
                .call()
                .content();

        responseJson = cleanJson(responseJson);
        return objectMapper.readValue(responseJson, new TypeReference<>() {});
    }

    // ─── System Analysis ────────────────────────────────────────────────

    private SystemAnalysis analyzeSystem(SystemSnapshotDto snapshot) {
        try {
            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("System: ").append(snapshot.getSystemTitle())
                    .append(" (").append(snapshot.getSystemId()).append(")\n\n");

            // Add schema summary
            if (snapshot.getSchemas() != null) {
                userPrompt.append("Actor Types: ").append(snapshot.getSchemas().getActorTypes()).append("\n");
                userPrompt.append("Item Types: ").append(snapshot.getSchemas().getItemTypes()).append("\n\n");

                // Add one actor schema as example
                for (var entry : snapshot.getSchemas().getActors().entrySet()) {
                    userPrompt.append("Schema for '").append(entry.getKey()).append("':\n");
                    String fieldsJson = objectMapper.writeValueAsString(entry.getValue().getFields());
                    // Truncate if too long
                    if (fieldsJson.length() > 4000) fieldsJson = fieldsJson.substring(0, 4000) + "...";
                    userPrompt.append(fieldsJson).append("\n\n");
                    break; // One schema type is enough for analysis
                }
            }

            // Add compendium samples
            if (snapshot.getCompendiumSamples() != null && snapshot.getCompendiumSamples().getActors() != null) {
                for (var entry : snapshot.getCompendiumSamples().getActors().entrySet()) {
                    List<Map<String, Object>> samples = entry.getValue();
                    if (!samples.isEmpty()) {
                        userPrompt.append("Sample ").append(entry.getKey()).append(" characters:\n");
                        for (int i = 0; i < Math.min(2, samples.size()); i++) {
                            String sampleJson = objectMapper.writeValueAsString(samples.get(i));
                            if (sampleJson.length() > 2000) sampleJson = sampleJson.substring(0, 2000) + "...";
                            userPrompt.append(sampleJson).append("\n");
                        }
                        userPrompt.append("\n");
                    }
                }
            }

            // Add value distributions
            if (snapshot.getValueDistributions() != null && !snapshot.getValueDistributions().isEmpty()) {
                userPrompt.append("Value distributions from existing characters:\n");
                userPrompt.append(objectMapper.writeValueAsString(snapshot.getValueDistributions())).append("\n\n");
            }

            // Add RAG context if manuals have been ingested
            try {
                String ragContext = ragService.searchCharacterCreationContext(
                        "character creation rules and steps", snapshot.getSystemId(), 5);
                if (!ragContext.isEmpty()) {
                    userPrompt.append("Rules from ingested manuals:\n");
                    if (ragContext.length() > 3000) ragContext = ragContext.substring(0, 3000) + "...";
                    userPrompt.append(ragContext).append("\n\n");
                }
            } catch (Exception e) {
                log.debug("RAG context unavailable during system analysis: {}", e.getMessage());
            }

            userPrompt.append("Analyze this RPG system.");

            String responseJson = chatClient.prompt()
                    .system(SYSTEM_ANALYSIS_PROMPT)
                    .user(u -> u.text("{userPrompt}").param("userPrompt", userPrompt.toString()))
                    .call()
                    .content();

            responseJson = cleanJson(responseJson);
            Map<String, Object> analysisMap = objectMapper.readValue(responseJson, Map.class);

            SystemAnalysis analysis = new SystemAnalysis();
            analysis.summary = (String) analysisMap.getOrDefault("systemSummary", "Unknown RPG system");
            analysis.creationSteps = analysisMap.containsKey("characterCreationSteps")
                    ? objectMapper.convertValue(analysisMap.get("characterCreationSteps"), new TypeReference<>() {})
                    : List.of();
            analysis.identityFields = analysisMap.containsKey("identityFields")
                    ? objectMapper.convertValue(analysisMap.get("identityFields"), new TypeReference<>() {})
                    : List.of();
            analysis.mechanicalFields = analysisMap.containsKey("mechanicalFields")
                    ? objectMapper.convertValue(analysisMap.get("mechanicalFields"), new TypeReference<>() {})
                    : List.of();
            analysis.constraints = analysisMap.containsKey("detectedConstraints")
                    ? objectMapper.convertValue(analysisMap.get("detectedConstraints"), new TypeReference<>() {})
                    : List.of();
            analysis.creationChoices = analysisMap.containsKey("creationChoices")
                    ? objectMapper.convertValue(analysisMap.get("creationChoices"), new TypeReference<>() {})
                    : Map.of();

            return analysis;

        } catch (Exception e) {
            log.warn("System analysis failed: {}", e.getMessage());
            SystemAnalysis fallback = new SystemAnalysis();
            fallback.summary = snapshot.getSystemTitle() + " RPG system";
            fallback.creationSteps = List.of();
            fallback.identityFields = List.of();
            fallback.mechanicalFields = List.of();
            fallback.constraints = List.of();
            fallback.creationChoices = Map.of();
            return fallback;
        }
    }

    // ─── Value Ranges ───────────────────────────────────────────────────

    private Map<String, SystemProfileDto.ValueRange> buildValueRanges(SystemSnapshotDto snapshot) {
        Map<String, SystemProfileDto.ValueRange> ranges = new HashMap<>();

        if (snapshot.getValueDistributions() == null) return ranges;

        for (var typeEntry : snapshot.getValueDistributions().entrySet()) {
            for (var fieldEntry : typeEntry.getValue().entrySet()) {
                SystemSnapshotDto.ValueDistribution dist = fieldEntry.getValue();
                ranges.put(fieldEntry.getKey(), SystemProfileDto.ValueRange.builder()
                        .min(dist.getMin())
                        .max(dist.getMax())
                        .typical(dist.getAvg())
                        .fieldType("number")
                        .build());
            }
        }

        return ranges;
    }

    // ─── Manual Enrichment ──────────────────────────────────────────────

    private void enrichFromManuals(SystemProfileDto profile, String systemId) {
        try {
            // Search for character creation rules
            String creationRules = ragService.searchCharacterCreationContext(
                    "How to create a character step by step", systemId, 5);

            if (!creationRules.isEmpty() && (profile.getCharacterCreationSteps() == null || profile.getCharacterCreationSteps().isEmpty())) {
                // Use AI to extract steps from the rules text
                String rulesText = creationRules.length() > 3000 ? creationRules.substring(0, 3000) : creationRules;
                String stepsJson = chatClient.prompt()
                        .system("Extract character creation steps from the following rules text. " +
                                "Return a JSON array of strings, each being one step. Respond ONLY with JSON.")
                        .user(u -> u.text("{userPrompt}").param("userPrompt", rulesText))
                        .call()
                        .content();

                stepsJson = cleanJson(stepsJson);
                List<String> steps = objectMapper.readValue(stepsJson, new TypeReference<>() {});
                profile.setCharacterCreationSteps(steps);
            }

            // Search for available choices (races, classes, etc.)
            String choicesContext = ragService.searchExtractedEntities(
                    "races classes archetypes available for character creation",
                    systemId, null, null, 10);

            if (!choicesContext.isEmpty() && (profile.getCreationChoices() == null || profile.getCreationChoices().isEmpty())) {
                String choicesText = choicesContext.length() > 3000 ? choicesContext.substring(0, 3000) : choicesContext;
                String choicesJson = chatClient.prompt()
                        .system("Extract available character creation choices from the text. " +
                                "Return JSON: {\"race\": [...], \"class\": [...], \"archetype\": [...]}. " +
                                "Only include categories that exist. Respond ONLY with JSON.")
                        .user(u -> u.text("{userPrompt}").param("userPrompt", choicesText))
                        .call()
                        .content();

                choicesJson = cleanJson(choicesJson);
                Map<String, List<String>> choices = objectMapper.readValue(choicesJson, new TypeReference<>() {});
                profile.setCreationChoices(choices);
            }

            profile.setEnrichedFromManuals(true);
        } catch (Exception e) {
            log.debug("Manual enrichment failed: {}", e.getMessage());
        }
    }

    // ─── Fallback Profile ───────────────────────────────────────────────

    private SystemProfileDto buildFallbackProfile(SystemSnapshotDto snapshot) {
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

    // ─── Utilities ──────────────────────────────────────────────────────

    private String prettifyGroupName(String key) {
        return key.substring(0, 1).toUpperCase() + key.substring(1)
                .replaceAll("([A-Z])", " $1")
                .replaceAll("_", " ")
                .trim();
    }

    private String inferCategory(String groupKey) {
        String lower = groupKey.toLowerCase();
        if (lower.contains("abilit") || lower.contains("atribut") || lower.contains("attr"))
            return "attributes";
        if (lower.contains("skill") || lower.contains("habilidad") || lower.contains("proficien"))
            return "skills";
        if (lower.contains("combat") || lower.contains("defens") || lower.contains("attack") ||
                lower.contains("armament") || lower.contains("blindaje") || lower.contains("danio"))
            return "combat";
        if (lower.contains("hp") || lower.contains("health") || lower.contains("resource") ||
                lower.contains("drama") || lower.contains("aguante") || lower.contains("resistencia"))
            return "resources";
        if (lower.contains("spell") || lower.contains("magic") || lower.contains("magia"))
            return "magic";
        if (lower.contains("bio") || lower.contains("concept") || lower.contains("descript") ||
                lower.contains("name") || lower.contains("persona"))
            return "identity";
        return "other";
    }

    private String cleanJson(String raw) {
        return dev.agiro.masterserver.util.JsonUtils.stripMarkdownFences(raw, "{}");
    }

    // Inner class for analysis result
    private static class SystemAnalysis {
        String summary;
        List<String> creationSteps;
        List<String> identityFields;
        List<String> mechanicalFields;
        List<SystemProfileDto.DetectedConstraint> constraints;
        Map<String, List<String>> creationChoices;
    }

    // ── Reference Character storage (delegated to OpenSearch repository) ──

    /**
     * Store a reference character for a given system + actor type.
     * Persisted in OpenSearch and cached in memory.
     */
    public void storeReferenceCharacter(ReferenceCharacterDto ref) {
        referenceCharacterRepository.save(ref);
        log.info("Stored reference character '{}' for {}:{}", ref.getLabel(), ref.getSystemId(), ref.getActorType());
    }

    /**
     * Retrieve the reference character for a given system + actor type.
     */
    public Optional<ReferenceCharacterDto> getReferenceCharacter(String systemId, String actorType) {
        return referenceCharacterRepository.find(systemId, actorType);
    }

    /**
     * Delete the reference character for a given system + actor type.
     */
    public void deleteReferenceCharacter(String systemId, String actorType) {
        referenceCharacterRepository.delete(systemId, actorType);
        log.info("Deleted reference character for {}:{}", systemId, actorType);
    }
}
