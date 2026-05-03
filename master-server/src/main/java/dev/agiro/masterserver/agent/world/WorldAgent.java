package dev.agiro.masterserver.agent.world;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.controller.WebSocketController;
import dev.agiro.masterserver.dto.*;
import dev.agiro.masterserver.tool.RAGService;
import dev.agiro.masterserver.tool.WorldStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * World Agent — Phase 3 implementation.
 * <p>
 * Capabilities:
 * <ul>
 *   <li>{@link #generateLocationAsync} — async location generation with WebSocket progress</li>
 *   <li>{@link #generateLocation} — sync variant</li>
 *   <li>{@link #logEvent} — persist a world event to OpenSearch + VectorStore</li>
 *   <li>{@link #saveFaction} / {@link #getFactions} / {@link #updateFaction} — faction management</li>
 *   <li>{@link #getWorldContext} — summarises persistent world state for context injection</li>
 * </ul>
 *
 * Memory:
 * <ul>
 *   <li>Persistent world state (events, factions, locations) in OpenSearch via
 *       {@link WorldStateRepository}.</li>
 *   <li>Cross-session JDBC {@link ChatMemory} scoped to {@code "world:<worldId>"}
 *       for narrative continuity in world-building conversations.</li>
 * </ul>
 */
@Slf4j
@Service
public class WorldAgent {

    private static final int RAG_TOP_K = 4;
    private static final int CONTEXT_EVENTS_LIMIT = 10;

    @Value("classpath:/prompts/world_location_system.txt")
    private Resource locationSystemPrompt;

    @Value("classpath:/prompts/world_faction_system.txt")
    private Resource factionSystemPrompt;

    private final ChatClient locationClient;
    private final ChatClient factionClient;
    private final ObjectMapper objectMapper;
    private final RAGService ragService;
    private final WorldStateRepository worldStateRepository;
    private final VectorStore vectorStore;
    private final ChatMemory chatMemory;
    private final WebSocketController webSocketController;

    public WorldAgent(ChatClient.Builder chatClientBuilder,
                      ObjectMapper objectMapper,
                      RAGService ragService,
                      WorldStateRepository worldStateRepository,
                      VectorStore vectorStore,
                      ChatMemory chatMemory,
                      WebSocketController webSocketController) {
        this.locationClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder().model("gpt-4.1-mini").temperature(0.9).build())
                .build();
        this.factionClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder().model("gpt-4o-mini").temperature(0.5).build())
                .build();
        this.objectMapper = objectMapper;
        this.ragService = ragService;
        this.worldStateRepository = worldStateRepository;
        this.vectorStore = vectorStore;
        this.chatMemory = chatMemory;
        this.webSocketController = webSocketController;
    }

    // ── Location Generation ───────────────────────────────────────────────

    /**
     * Async location generation — returns immediately and pushes result via WebSocket.
     */
    @Async
    public void generateLocationAsync(LocationRequest request) {
        String sessionId = request.getSessionId();
        sendWorldUpdate(sessionId, WebSocketMessage.success(
                WebSocketMessage.MessageType.LOCATION_GENERATION_STARTED, sessionId,
                Map.of("message", "Generating location...", "prompt", request.getPrompt())));
        try {
            LocationResponse response = generateLocation(request);
            WebSocketMessage.MessageType type = response.isSuccess()
                    ? WebSocketMessage.MessageType.LOCATION_GENERATION_COMPLETED
                    : WebSocketMessage.MessageType.LOCATION_GENERATION_FAILED;
            sendWorldUpdate(sessionId, response.isSuccess()
                    ? WebSocketMessage.success(type, sessionId, response)
                    : WebSocketMessage.error(type, sessionId, response.getReasoning()));
        } catch (Exception e) {
            log.error("[WorldAgent] Async location generation failed", e);
            sendWorldUpdate(sessionId, WebSocketMessage.error(
                    WebSocketMessage.MessageType.LOCATION_GENERATION_FAILED, sessionId,
                    "Location generation failed: " + e.getMessage()));
        }
    }

    /**
     * Sync location generation — produces and persists a full {@link LocationResponse}.
     */
    public LocationResponse generateLocation(LocationRequest request) {
        log.info("[WorldAgent] Generating location: '{}' (type={}, world={})",
                request.getPrompt(), request.getLocationType(), request.getWorldId());
        try {
            String systemPrompt = readPrompt(locationSystemPrompt, request.getLanguage());
            String userPrompt = buildLocationPrompt(request);

            String convId = "world:" + Optional.ofNullable(request.getWorldId()).orElse("default");
            String raw = locationClient.prompt()
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory).conversationId(convId).build())
                    .system(systemPrompt)
                    .user(u -> u.text("{p}").param("p", userPrompt))
                    .call().content();

            LocationResponse response = parseLocationResponse(raw, request.getWorldId());
            if (response.isSuccess() && request.getWorldId() != null) {
                worldStateRepository.saveLocation(response);
                ingestLocationIntoRAG(response, request);
                log.info("[WorldAgent] Location '{}' saved (id={})", response.getName(), response.getLocationId());
            }
            return response;
        } catch (Exception e) {
            log.error("[WorldAgent] Location generation failed", e);
            LocationResponse err = new LocationResponse();
            err.setSuccess(false);
            err.setReasoning("Location generation failed: " + e.getMessage());
            return err;
        }
    }

    // ── World Events ─────────────────────────────────────────────────────

    /**
     * Persist a world event and ingest it into the VectorStore for future RAG retrieval.
     * This is the "learning loop" — every significant in-game event becomes searchable context.
     */
    public WorldEventDto logEvent(WorldEventDto event) {
        if (event.getEventId() == null) event.setEventId(UUID.randomUUID().toString());
        if (event.getTimestamp() == null) event.setTimestamp(Instant.now().toEpochMilli());

        worldStateRepository.saveEvent(event);
        ingestEventIntoRAG(event);

        if (event.getSessionId() != null) {
            sendWorldUpdate(event.getSessionId(), WebSocketMessage.success(
                    WebSocketMessage.MessageType.WORLD_EVENT_LOGGED, event.getSessionId(), event));
        }
        log.info("[WorldAgent] Event logged: '{}' (world={}, importance={})",
                event.getTitle(), event.getWorldId(), event.getImportance());
        return event;
    }

    // ── Faction Management ────────────────────────────────────────────────

    public List<FactionDto> getFactions(String worldId) {
        return worldStateRepository.findFactionsByWorld(worldId);
    }

    public Optional<FactionDto> getFaction(String factionId) {
        return worldStateRepository.findFaction(factionId);
    }

    /**
     * Create or update a faction, enriching it with LLM-generated narrative details
     * based on recent world events.
     */
    public FactionDto saveFaction(FactionDto faction) {
        try {
            String systemPrompt = readPrompt(factionSystemPrompt, null);
            String worldContext = buildWorldContextSummary(faction.getWorldId(), 5);
            String userPrompt = "WORLD CONTEXT:\n" + worldContext + "\n\nFACTION TO UPDATE:\n"
                    + objectMapper.writeValueAsString(faction);

            String raw = factionClient.prompt()
                    .system(systemPrompt)
                    .user(u -> u.text("{p}").param("p", userPrompt))
                    .call().content();

            enrichFactionFromLlm(faction, raw);
        } catch (Exception e) {
            log.warn("[WorldAgent] LLM faction enrichment failed: {}", e.getMessage());
        }

        worldStateRepository.saveFaction(faction);
        ingestFactionIntoRAG(faction);

        if (faction.getWorldId() != null) {
            log.info("[WorldAgent] Faction '{}' saved (world={})", faction.getName(), faction.getWorldId());
        }
        return faction;
    }

    public FactionDto updateFaction(FactionDto faction) {
        return saveFaction(faction);
    }

    public void deleteFaction(String factionId) {
        worldStateRepository.deleteFaction(factionId);
    }

    // ── World Context ─────────────────────────────────────────────────────

    /**
     * Builds a world context summary suitable for injection into other agent prompts.
     * Combines recent significant events, active factions, and known locations.
     */
    public String getWorldContext(String worldId) {
        return buildWorldContextSummary(worldId, CONTEXT_EVENTS_LIMIT);
    }

    /**
     * Returns the N most recent locations generated for a world.
     */
    public List<LocationResponse> getLocations(String worldId) {
        return worldStateRepository.findLocationsByWorld(worldId);
    }

    public WorldStateRepository getWorldStateRepository() {
        return worldStateRepository;
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private String buildLocationPrompt(LocationRequest request) {
        StringBuilder sb = new StringBuilder();

        // Inject persistent world context
        if (request.getWorldId() != null) {
            String ctx = buildWorldContextSummary(request.getWorldId(), 5);
            if (!ctx.isBlank()) {
                sb.append("=== WORLD CONTEXT ===\n").append(ctx).append("\n\n");
            }
        }

        // Rules/lore context from RAG
        if (request.getSystemId() != null) {
            String rules = ragService.searchRulesContext(request.getPrompt(), request.getSystemId(), RAG_TOP_K);
            if (!rules.isBlank()) {
                sb.append("=== RULES CONTEXT ===\n").append(truncate(rules, 2000)).append("\n\n");
            }
        }

        sb.append("=== LOCATION REQUEST ===\n");
        sb.append("Prompt: ").append(request.getPrompt()).append("\n");
        sb.append("Type: ").append(Optional.ofNullable(request.getLocationType()).orElse("dungeon")).append("\n");
        sb.append("Size: ").append(Optional.ofNullable(request.getSize()).orElse("medium")).append("\n");
        sb.append("Party level: ").append(Optional.ofNullable(request.getPartyLevel()).orElse(1)).append("\n");
        if (request.getControllingFaction() != null) {
            sb.append("Controlling faction: ").append(request.getControllingFaction()).append("\n");
        }
        return sb.toString();
    }

    private String buildWorldContextSummary(String worldId, int eventLimit) {
        if (worldId == null) return "";
        StringBuilder sb = new StringBuilder();

        List<WorldEventDto> events = worldStateRepository.findSignificantEvents(worldId, eventLimit);
        if (!events.isEmpty()) {
            sb.append("SIGNIFICANT EVENTS:\n");
            events.forEach(e -> sb.append("  [").append(e.getImportance()).append("] ")
                    .append(e.getTitle()).append(": ").append(e.getDescription()).append("\n"));
        }

        List<FactionDto> factions = worldStateRepository.findFactionsByWorld(worldId);
        if (!factions.isEmpty()) {
            sb.append("ACTIVE FACTIONS:\n");
            factions.forEach(f -> sb.append("  ").append(f.getName())
                    .append(" — ").append(f.getCurrentStatus()).append("\n"));
        }

        List<LocationResponse> locations = worldStateRepository.findLocationsByWorld(worldId);
        if (!locations.isEmpty()) {
            sb.append("KNOWN LOCATIONS:\n");
            locations.stream().limit(5).forEach(l ->
                    sb.append("  ").append(l.getName()).append(" (").append(l.getType()).append(")\n"));
        }

        return sb.toString();
    }

    private LocationResponse parseLocationResponse(String raw, String worldId) {
        try {
            Map<String, Object> map = objectMapper.readValue(cleanJson(raw), new TypeReference<>() {});
            LocationResponse response = new LocationResponse();
            response.setSuccess(true);
            response.setLocationId(UUID.randomUUID().toString());
            response.setWorldId(worldId);
            response.setName((String) map.get("name"));
            response.setType((String) map.get("type"));
            response.setDescription((String) map.get("description"));
            response.setAtmosphere((String) map.get("atmosphere"));
            response.setReasoning((String) map.get("reasoning"));

            Object factionsObj = map.get("presentFactions");
            if (factionsObj instanceof List<?> fl) {
                response.setPresentFactions(fl.stream().map(Object::toString).collect(Collectors.toList()));
            }

            Object roomsObj = map.get("rooms");
            if (roomsObj instanceof List<?> rl) {
                List<LocationResponse.RoomDto> rooms = rl.stream()
                        .filter(r -> r instanceof Map)
                        .map(r -> objectMapper.convertValue(r, LocationResponse.RoomDto.class))
                        .collect(Collectors.toList());
                response.setRooms(rooms);
            }
            return response;
        } catch (Exception e) {
            log.warn("[WorldAgent] Failed to parse location response: {}", e.getMessage());
            LocationResponse err = new LocationResponse();
            err.setSuccess(false);
            err.setReasoning("Parse failed: " + e.getMessage());
            return err;
        }
    }

    @SuppressWarnings("unchecked")
    private void enrichFactionFromLlm(FactionDto faction, String raw) {
        try {
            Map<String, Object> map = objectMapper.readValue(cleanJson(raw), new TypeReference<>() {});
            if (map.containsKey("currentStatus")) faction.setCurrentStatus((String) map.get("currentStatus"));
            if (map.containsKey("goals")) faction.setGoals((List<String>) map.get("goals"));
            if (map.containsKey("allies")) faction.setAllies((List<String>) map.get("allies"));
            if (map.containsKey("enemies")) faction.setEnemies((List<String>) map.get("enemies"));
        } catch (Exception e) {
            log.debug("[WorldAgent] Faction LLM enrichment parse failed: {}", e.getMessage());
        }
    }

    // ── RAG Ingestion ─────────────────────────────────────────────────────

    private void ingestEventIntoRAG(WorldEventDto event) {
        try {
            String content = String.format("[%s] %s: %s", event.getImportance(), event.getTitle(), event.getDescription());
            Document doc = new Document(content, Map.of(
                    "chunkType", "world_event",
                    "worldId", Optional.ofNullable(event.getWorldId()).orElse(""),
                    "eventType", Optional.ofNullable(event.getEventType()).orElse(""),
                    "importance", Optional.ofNullable(event.getImportance()).orElse("minor"),
                    "eventId", event.getEventId()
            ));
            vectorStore.add(List.of(doc));
        } catch (Exception e) {
            log.debug("[WorldAgent] Event RAG ingestion failed: {}", e.getMessage());
        }
    }

    private void ingestLocationIntoRAG(LocationResponse location, LocationRequest request) {
        try {
            StringBuilder content = new StringBuilder();
            content.append("LOCATION: ").append(location.getName()).append("\n");
            content.append("Type: ").append(location.getType()).append("\n");
            content.append(location.getDescription()).append("\n");
            if (location.getRooms() != null) {
                location.getRooms().forEach(r -> content.append("\n  Room '").append(r.getName())
                        .append("': ").append(r.getDescription()));
            }
            Document doc = new Document(content.toString(), Map.of(
                    "chunkType", "world_location",
                    "worldId", Optional.ofNullable(location.getWorldId()).orElse(""),
                    "systemId", Optional.ofNullable(request.getSystemId()).orElse(""),
                    "locationType", Optional.ofNullable(location.getType()).orElse(""),
                    "locationId", location.getLocationId()
            ));
            vectorStore.add(List.of(doc));
        } catch (Exception e) {
            log.debug("[WorldAgent] Location RAG ingestion failed: {}", e.getMessage());
        }
    }

    private void ingestFactionIntoRAG(FactionDto faction) {
        try {
            String content = String.format("FACTION: %s — %s. Status: %s. Goals: %s",
                    faction.getName(), faction.getDescription(),
                    faction.getCurrentStatus(),
                    faction.getGoals() != null ? String.join(", ", faction.getGoals()) : "");
            Document doc = new Document(content, Map.of(
                    "chunkType", "world_faction",
                    "worldId", Optional.ofNullable(faction.getWorldId()).orElse(""),
                    "factionId", Optional.ofNullable(faction.getFactionId()).orElse("")
            ));
            vectorStore.add(List.of(doc));
        } catch (Exception e) {
            log.debug("[WorldAgent] Faction RAG ingestion failed: {}", e.getMessage());
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private String readPrompt(Resource resource, String language) {
        try {
            String base = resource.getContentAsString(StandardCharsets.UTF_8);
            return language != null ? base.replace("{language}", language) : base;
        } catch (Exception e) {
            return "You are an expert Game Master assistant.";
        }
    }

    private void sendWorldUpdate(String sessionId, WebSocketMessage message) {
        if (sessionId != null) webSocketController.sendWorldUpdate(sessionId, message);
    }

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String t = raw.trim();
        if (t.startsWith("```json")) t = t.substring(7);
        else if (t.startsWith("```")) t = t.substring(3);
        if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        return t.trim();
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}

