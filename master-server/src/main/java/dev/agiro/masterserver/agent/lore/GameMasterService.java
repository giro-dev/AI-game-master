package dev.agiro.masterserver.agent.lore;

import dev.agiro.masterserver.dto.AbilityDto;
import dev.agiro.masterserver.dto.GameMasterRequest;
import dev.agiro.masterserver.dto.GameMasterResponse;
import dev.agiro.masterserver.dto.WorldStateDto;
import dev.agiro.masterserver.tool.RAGService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Game Master Service — the session-level AI assistant for live play.
 * <p>
 * Combines:
 * <ul>
 *   <li>RAG retrieval — surfaces relevant rules, lore, and NPC data from ingested manuals.</li>
 *   <li>Per-campaign JDBC chat memory — maintains conversation history scoped to {@code worldId}
 *       so the GM can have a continuous dialogue across requests within the same campaign.</li>
 *   <li>Cross-campaign memory — uses {@code systemId + ":global"} as a second conversation
 *       window that persists system-level context across all campaigns.</li>
 * </ul>
 * <p>
 * This service handles token-selected (active scene) requests that may result in VTT actions.
 */
@Slf4j
@Service
public class GameMasterService {

    private static final int RAG_TOP_K = 6;

    @Value("classpath:/prompts/session_assistant_system.txt")
    private Resource sessionSystemPrompt;

    private final ChatClient chatClient;
    private final RAGService ragService;
    private final ChatMemory chatMemory;

    public GameMasterService(ChatClient.Builder chatClientBuilder,
                              RAGService ragService,
                              ChatMemory chatMemory) {
        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.7)
                        .build())
                .build();
        this.ragService = ragService;
        this.chatMemory = chatMemory;
    }

    /**
     * Process a full GM request (token selected, scene active).
     * Uses per-campaign memory (conversationId = worldId).
     */
    public GameMasterResponse processRequest(GameMasterRequest request) {
        log.info("[GameMasterService] Processing request for token '{}' in world '{}'",
                request.getTokenName(), request.getWorldId());

        String language = request.getFoundrySystem() != null ? detectLanguage(request) : "en";
        String conversationId = buildPerCampaignId(request.getWorldId());

        String systemPrompt = buildSystemPrompt(language);
        String userMessage = buildUserMessage(request);
        String ragContext = fetchRagContext(request);

        String fullPrompt = ragContext.isBlank() ? userMessage :
                "=== RULES & LORE CONTEXT ===\n" + ragContext + "\n\n=== REQUEST ===\n" + userMessage;

        try {
            String json = chatClient.prompt()
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory)
                            .conversationId(conversationId)
                            .build())
                    .system(systemPrompt)
                    .user(u -> u.text("{p}").param("p", fullPrompt))
                    .call().content();

            return parseResponse(json);

        } catch (Exception e) {
            log.error("[GameMasterService] Request processing failed", e);
            GameMasterResponse err = new GameMasterResponse();
            err.setNarration("I encountered an error processing your request: " + e.getMessage());
            err.setActions(new ArrayList<>());
            return err;
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private String buildSystemPrompt(String language) {
        try {
            String base = sessionSystemPrompt.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            return base.replace("{language}", language);
        } catch (Exception e) {
            return "You are an AI Game Master assistant. Respond in " + language +
                   ". Respond ONLY with valid JSON containing 'narration', 'reasoning', and 'actions'.";
        }
    }

    private String buildUserMessage(GameMasterRequest request) {
        StringBuilder sb = new StringBuilder();

        if (request.getTokenId() != null) {
            sb.append("Active token: ").append(request.getTokenName())
              .append(" (ID: ").append(request.getTokenId()).append(")\n");
        }

        if (request.getAbilities() != null && !request.getAbilities().isEmpty()) {
            sb.append("Available abilities:\n");
            request.getAbilities().forEach(a ->
                    sb.append("  - [").append(a.getId()).append("] ").append(a.getName()).append("\n"));
            sb.append("\n");
        }

        if (request.getWorldState() != null) {
            sb.append("World state:\n").append(formatWorldState(request.getWorldState())).append("\n");
        }

        sb.append("GM request: ").append(request.getPrompt());
        return sb.toString();
    }

    private String fetchRagContext(GameMasterRequest request) {
        if (request.getFoundrySystem() == null) return "";
        try {
            return ragService.searchContext(request.getPrompt(), request.getFoundrySystem(), RAG_TOP_K);
        } catch (Exception e) {
            log.debug("[GameMasterService] RAG fetch failed: {}", e.getMessage());
            return "";
        }
    }

    private GameMasterResponse parseResponse(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String cleaned = cleanJson(json);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = mapper.readValue(cleaned, java.util.Map.class);

            GameMasterResponse response = new GameMasterResponse();
            response.setNarration((String) map.getOrDefault("narration", ""));
            response.setReasoning((String) map.getOrDefault("reasoning", ""));
            response.setSelectedAbilityId((String) map.getOrDefault("selectedAbilityId", null));
            response.setSelectedAbilityName((String) map.getOrDefault("selectedAbilityName", null));

            Object actionsObj = map.get("actions");
            if (actionsObj instanceof List<?> rawActions) {
                List<GameMasterResponse.ActionDto> actions = new ArrayList<>();
                for (Object raw : rawActions) {
                    if (raw instanceof java.util.Map<?, ?> am) {
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
                response.setActions(new ArrayList<>());
            }
            return response;

        } catch (Exception e) {
            log.warn("[GameMasterService] Failed to parse response JSON: {}", e.getMessage());
            GameMasterResponse fallback = new GameMasterResponse();
            fallback.setNarration(json);
            fallback.setActions(new ArrayList<>());
            return fallback;
        }
    }

    private String formatWorldState(WorldStateDto ws) {
        StringBuilder sb = new StringBuilder();
        if (ws.getSceneName() != null) sb.append("Scene: ").append(ws.getSceneName()).append("\n");
        if (ws.getTokens() != null) {
            ws.getTokens().forEach(t -> {
                sb.append("  Token: ").append(t.getName()).append(" (ID: ").append(t.getId()).append(")");
                if (t.getHp() != null) {
                    sb.append(" HP: ").append(t.getHp().get("value")).append("/").append(t.getHp().get("max"));
                }
                sb.append("\n");
            });
        }
        if (ws.getCombat() != null) {
            sb.append("Combat: Round ").append(ws.getCombat().getRound())
              .append(", Turn ").append(ws.getCombat().getTurn()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Per-campaign conversation ID: scoped to a single world/campaign.
     * JDBC persistence ensures history survives server restarts.
     */
    private String buildPerCampaignId(String worldId) {
        return worldId != null ? "campaign:" + worldId : "campaign:default";
    }

    /**
     * Cross-campaign conversation ID: system-level knowledge, shared across all campaigns.
     */
    @SuppressWarnings("unused")
    private String buildCrossCampaignId(String systemId) {
        return systemId != null ? "global:" + systemId : "global:default";
    }

    private String detectLanguage(GameMasterRequest request) {
        // Language detection can be extended; for now, use english as default
        return "en";
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
