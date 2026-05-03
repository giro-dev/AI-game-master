package dev.agiro.masterserver.agent.combat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.controller.WebSocketController;
import dev.agiro.masterserver.dto.*;
import dev.agiro.masterserver.tool.RAGService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Combat Agent — Phase 2 implementation.
 * <p>
 * Capabilities:
 * <ul>
 *   <li>{@link #designEncounterAsync} — async encounter design with WebSocket progress</li>
 *   <li>{@link #designEncounter} — sync variant (used by WS handler)</li>
 *   <li>{@link #adviseAction} — live combat advice for the active token, memory-backed</li>
 *   <li>{@link #distributeLoot} — post-combat XP + loot distribution</li>
 * </ul>
 *
 * Memory: per-campaign JDBC {@code ChatMemory} scoped to {@code "combat:<worldId>"}
 * for combat advice continuity within a session.
 */
@Slf4j
@Service
public class CombatAgent {

    private static final int RAG_TOP_K = 4;

    @Value("classpath:/prompts/combat_encounter_system.txt")
    private Resource encounterSystemPrompt;

    @Value("classpath:/prompts/combat_advice_system.txt")
    private Resource adviceSystemPrompt;

    private final ChatClient encounterClient;
    private final ChatClient adviceClient;
    private final ObjectMapper objectMapper;
    private final RAGService ragService;
    private final ChatMemory chatMemory;
    private final WebSocketController webSocketController;

    public CombatAgent(ChatClient.Builder chatClientBuilder,
                       ObjectMapper objectMapper,
                       RAGService ragService,
                       ChatMemory chatMemory,
                       WebSocketController webSocketController) {
        this.encounterClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder().model("gpt-4.1-mini").temperature(0.85).build())
                .build();
        this.adviceClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder().model("gpt-4o-mini").temperature(0.4).build())
                .build();
        this.objectMapper = objectMapper;
        this.ragService = ragService;
        this.chatMemory = chatMemory;
        this.webSocketController = webSocketController;
    }

    // ── Encounter Design ─────────────────────────────────────────────────

    /**
     * Async encounter design — returns immediately and pushes results via WebSocket.
     */
    @Async
    public void designEncounterAsync(EncounterRequest request) {
        String sessionId = request.getSessionId();
        sendCombatUpdate(sessionId, WebSocketMessage.success(
                WebSocketMessage.MessageType.ENCOUNTER_GENERATION_STARTED, sessionId,
                Map.of("message", "Designing encounter...", "prompt", request.getPrompt())));
        try {
            EncounterResponse response = designEncounter(request);
            WebSocketMessage.MessageType type = response.isSuccess()
                    ? WebSocketMessage.MessageType.ENCOUNTER_GENERATION_COMPLETED
                    : WebSocketMessage.MessageType.ENCOUNTER_GENERATION_FAILED;
            sendCombatUpdate(sessionId, response.isSuccess()
                    ? WebSocketMessage.success(type, sessionId, response)
                    : WebSocketMessage.error(type, sessionId, response.getReasoning()));
        } catch (Exception e) {
            log.error("[CombatAgent] Async encounter design failed", e);
            sendCombatUpdate(sessionId, WebSocketMessage.error(
                    WebSocketMessage.MessageType.ENCOUNTER_GENERATION_FAILED, sessionId,
                    "Encounter design failed: " + e.getMessage()));
        }
    }

    /**
     * Sync encounter design — called by the WS handler and HTTP controller.
     */
    public EncounterResponse designEncounter(EncounterRequest request) {
        log.info("[CombatAgent] Designing encounter: '{}' (system={}, party={} lvl{})",
                request.getPrompt(), request.getSystemId(), request.getPartySize(), request.getPartyLevel());
        try {
            String systemPrompt = readPrompt(encounterSystemPrompt, request.getLanguage());
            String userPrompt = buildEncounterPrompt(request);
            String raw = encounterClient.prompt()
                    .system(systemPrompt)
                    .user(u -> u.text("{p}").param("p", userPrompt))
                    .call().content();
            return parseEncounterResponse(raw);
        } catch (Exception e) {
            log.error("[CombatAgent] Encounter design failed", e);
            EncounterResponse err = new EncounterResponse();
            err.setSuccess(false);
            err.setReasoning("Encounter design failed: " + e.getMessage());
            return err;
        }
    }

    // ── Live Combat Advice ───────────────────────────────────────────────

    /**
     * Provide tactical advice for the currently active token.
     * Uses per-campaign JDBC memory scoped to {@code "combat:<worldId>"}.
     */
    public CombatAdviceResponse adviseAction(CombatAdviceRequest request) {
        log.info("[CombatAgent] Combat advice for token '{}' in world '{}'",
                request.getActiveTokenName(), request.getWorldId());
        try {
            String convId = buildCombatConversationId(request.getWorldId(), request.getConversationId());
            String systemPrompt = readPrompt(adviceSystemPrompt, null);
            String userPrompt = buildAdvicePrompt(request);

            // Enrich with rules context from RAG
            String rules = "";
            if (request.getSystemId() != null) {
                rules = ragService.searchRulesContext(request.getPrompt(), request.getSystemId(), RAG_TOP_K);
            }
            String full = rules.isBlank() ? userPrompt
                    : "=== RULES CONTEXT ===\n" + truncate(rules, 2000) + "\n\n=== REQUEST ===\n" + userPrompt;

            String raw = adviceClient.prompt()
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory).conversationId(convId).build())
                    .system(systemPrompt)
                    .user(u -> u.text("{p}").param("p", full))
                    .call().content();

            return parseAdviceResponse(raw);
        } catch (Exception e) {
            log.error("[CombatAgent] Combat advice failed", e);
            CombatAdviceResponse err = new CombatAdviceResponse();
            err.setNarration("I could not determine the best action: " + e.getMessage());
            err.setActions(List.of());
            return err;
        }
    }

    // ── Post-combat Loot ─────────────────────────────────────────────────

    /**
     * Generate XP/loot distribution after a completed encounter.
     * Async — pushes result via WebSocket.
     */
    @Async
    public void distributeLootAsync(EncounterResponse encounter, String worldId, String sessionId, String systemId) {
        try {
            List<Map<String, Object>> loot = encounter.getRecommendedLoot();
            int totalXp = encounter.getEstimatedXp() != null ? encounter.getEstimatedXp() : 0;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("encounterId", encounter.getEncounterId());
            result.put("totalXp", totalXp);
            result.put("loot", loot != null ? loot : List.of());
            result.put("reasoning", "Distributed based on encounter combatants");

            sendCombatUpdate(sessionId, WebSocketMessage.success(
                    WebSocketMessage.MessageType.LOOT_DISTRIBUTION_COMPLETED, sessionId, result));
        } catch (Exception e) {
            log.error("[CombatAgent] Loot distribution failed", e);
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private String buildEncounterPrompt(EncounterRequest request) {
        StringBuilder sb = new StringBuilder();

        // RAG creature context
        if (request.getSystemId() != null) {
            String npcCtx = ragService.searchNpcContext(
                    request.getPrompt() + " " + Optional.ofNullable(request.getTerrain()).orElse(""),
                    request.getSystemId(), RAG_TOP_K);
            if (!npcCtx.isBlank()) {
                sb.append("=== CREATURE REFERENCE FROM MANUALS ===\n")
                  .append(truncate(npcCtx, 2500)).append("\n\n");
            }
        }

        sb.append("=== ENCOUNTER REQUEST ===\n");
        sb.append("Game system: ").append(Optional.ofNullable(request.getSystemId()).orElse("generic")).append("\n");
        sb.append("Prompt: ").append(request.getPrompt()).append("\n");
        sb.append("Party size: ").append(Optional.ofNullable(request.getPartySize()).orElse(4)).append(" characters\n");
        sb.append("Party level: ").append(Optional.ofNullable(request.getPartyLevel()).orElse(1)).append("\n");
        sb.append("Difficulty: ").append(Optional.ofNullable(request.getDifficulty()).orElse("medium")).append("\n");
        if (request.getTerrain() != null) sb.append("Terrain: ").append(request.getTerrain()).append("\n");
        if (request.getAllowedCreatureTypes() != null && !request.getAllowedCreatureTypes().isEmpty()) {
            sb.append("Creature types: ").append(request.getAllowedCreatureTypes()).append("\n");
        }
        return sb.toString();
    }

    private String buildAdvicePrompt(CombatAdviceRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Active token: ").append(request.getActiveTokenName());
        if (request.getActiveTokenId() != null) sb.append(" (ID: ").append(request.getActiveTokenId()).append(")");
        sb.append("\n\n");

        if (request.getAbilities() != null && !request.getAbilities().isEmpty()) {
            sb.append("Available abilities:\n");
            request.getAbilities().forEach(a ->
                    sb.append("  - [").append(a.getId()).append("] ").append(a.getName())
                      .append(" (type: ").append(a.getType()).append(")\n"));
            sb.append("\n");
        }

        if (request.getWorldState() != null) {
            sb.append("Scene: ").append(Optional.ofNullable(request.getWorldState().getSceneName()).orElse("unknown")).append("\n");
            if (request.getWorldState().getTokens() != null) {
                sb.append("Tokens in scene:\n");
                request.getWorldState().getTokens().forEach(t -> {
                    sb.append("  - ").append(t.getName()).append(" (ID: ").append(t.getId()).append(")");
                    if (t.getHp() != null) {
                        sb.append(" HP: ").append(t.getHp().get("value")).append("/").append(t.getHp().get("max"));
                    }
                    sb.append("\n");
                });
            }
            if (request.getWorldState().getCombat() != null) {
                sb.append("Round: ").append(request.getWorldState().getCombat().getRound()).append("\n");
            }
        }

        sb.append("\nGM request: ").append(request.getPrompt());
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private EncounterResponse parseEncounterResponse(String raw) {
        try {
            Map<String, Object> map = objectMapper.readValue(cleanJson(raw), new TypeReference<>() {});
            EncounterResponse response = new EncounterResponse();
            response.setSuccess(true);
            response.setEncounterId(UUID.randomUUID().toString());
            response.setTitle((String) map.get("title"));
            response.setDescription((String) map.get("description"));
            response.setReasoning((String) map.get("reasoning"));
            response.setEstimatedXp(toInt(map.get("estimatedXp")));

            Object rawLoot = map.get("recommendedLoot");
            if (rawLoot instanceof List<?> ll) {
                response.setRecommendedLoot((List<Map<String, Object>>) ll);
            }

            Object terrainObj = map.get("terrain");
            if (terrainObj instanceof Map<?, ?> tm) {
                response.setTerrain(objectMapper.convertValue(tm, EncounterResponse.TerrainDto.class));
            }

            Object combatantsObj = map.get("combatants");
            if (combatantsObj instanceof List<?> cl) {
                List<EncounterResponse.CombatantDto> combatants = cl.stream()
                        .filter(c -> c instanceof Map)
                        .map(c -> objectMapper.convertValue(c, EncounterResponse.CombatantDto.class))
                        .toList();
                response.setCombatants(combatants);
            }

            return response;
        } catch (Exception e) {
            log.warn("[CombatAgent] Failed to parse encounter response: {}", e.getMessage());
            EncounterResponse err = new EncounterResponse();
            err.setSuccess(false);
            err.setReasoning("Parse failed: " + e.getMessage());
            return err;
        }
    }

    @SuppressWarnings("unchecked")
    private CombatAdviceResponse parseAdviceResponse(String raw) {
        try {
            Map<String, Object> map = objectMapper.readValue(cleanJson(raw), new TypeReference<>() {});
            CombatAdviceResponse response = new CombatAdviceResponse();
            response.setNarration((String) map.get("narration"));
            response.setSuggestedAction((String) map.get("suggestedAction"));
            response.setReasoning((String) map.get("reasoning"));
            response.setSelectedAbilityId((String) map.get("selectedAbilityId"));
            response.setSelectedAbilityName((String) map.get("selectedAbilityName"));

            Object actionsObj = map.get("actions");
            if (actionsObj instanceof List<?> al) {
                List<GameMasterResponse.ActionDto> actions = new ArrayList<>();
                for (Object rawAction : al) {
                    if (rawAction instanceof Map<?, ?> am) {
                        GameMasterResponse.ActionDto action = new GameMasterResponse.ActionDto();
                        action.setType((String) am.get("type"));
                        action.setTokenId((String) am.get("tokenId"));
                        action.setAbilityId((String) am.get("abilityId"));
                        action.setAbility((String) am.get("ability"));
                        action.setSkill((String) am.get("skill"));
                        action.setTarget((String) am.get("target"));
                        if (am.get("amount") instanceof Number n) action.setAmount(n.intValue());
                        actions.add(action);
                    }
                }
                response.setActions(actions);
            } else {
                response.setActions(List.of());
            }
            return response;
        } catch (Exception e) {
            log.warn("[CombatAgent] Failed to parse advice response: {}", e.getMessage());
            CombatAdviceResponse err = new CombatAdviceResponse();
            err.setNarration(raw);
            err.setActions(List.of());
            return err;
        }
    }

    private String readPrompt(Resource resource, String language) {
        try {
            String base = resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            return language != null ? base.replace("{language}", language) : base;
        } catch (Exception e) {
            return "You are an expert Game Master assistant.";
        }
    }

    private String buildCombatConversationId(String worldId, String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) return "combat:" + conversationId;
        return "combat:" + (worldId != null ? worldId : "default");
    }

    private void sendCombatUpdate(String sessionId, WebSocketMessage message) {
        if (sessionId != null) webSocketController.sendCombatUpdate(sessionId, message);
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

    private Integer toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return null;
    }
}

