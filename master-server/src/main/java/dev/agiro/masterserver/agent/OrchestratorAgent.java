package dev.agiro.masterserver.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.agent.character.CharacterAgent;
import dev.agiro.masterserver.agent.combat.CombatAgent;
import dev.agiro.masterserver.agent.item.ItemGenerationService;
import dev.agiro.masterserver.agent.lore.GameMasterManualSolver;
import dev.agiro.masterserver.agent.lore.GameMasterService;
import dev.agiro.masterserver.agent.world.WorldAgent;
import dev.agiro.masterserver.dto.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

/**
 * Orchestrator Agent — the brain of the multi-agent system.
 * <p>
 * Routes incoming natural-language requests to specialist agents based on intent.
 *
 * <ul>
 *   <li>Phase 1: keyword heuristic classification (fast, no LLM cost).</li>
 *   <li>Phase 2+: LLM-powered intent classification via
 *       {@link #classifyIntentWithLlm(String)} — falls back to heuristic on error.</li>
 * </ul>
 *
 * The {@code /gm/orchestrate} endpoint (see OrchestratorController or GameMasterController)
 * uses this agent to route unified GM requests without the caller knowing which
 * specialist handles the work.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Getter
public class OrchestratorAgent {

    private final CharacterAgent characterAgent;
    private final GameMasterService sessionChatAgent;
    private final GameMasterManualSolver loreAgent;
    private final ItemGenerationService itemAgent;
    private final CombatAgent combatAgent;
    private final WorldAgent worldAgent;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;

    // ── Intent Classification ─────────────────────────────────────────────

    /**
     * Classify the intent of a user message using LLM-powered reasoning.
     * Falls back to {@link #classifyIntentHeuristic(String)} on any error.
     */
    public AgentIntent classifyIntent(String userMessage) {
        try {
            return classifyIntentWithLlm(userMessage);
        } catch (Exception e) {
            log.debug("[Orchestrator] LLM classification failed, using heuristic: {}", e.getMessage());
            return classifyIntentHeuristic(userMessage);
        }
    }

    /**
     * LLM-powered intent classification.
     * Returns one of the {@link AgentIntent} enum values.
     */
    public AgentIntent classifyIntentWithLlm(String userMessage) {
        ChatClient client = chatClientBuilder
                .defaultOptions(ChatOptions.builder().model("gpt-4o-mini").temperature(0.0).build())
                .build();

        String systemPrompt = """
                You are an intent classifier for a tabletop RPG assistant.
                
                Given a user message, classify it into exactly ONE of the following intents:
                
                - CHARACTER   — creating, modifying, or discussing a character, NPC, or creature
                - ITEM        — generating, identifying, or modifying items, weapons, armor, spells
                - COMBAT      — encounter design, tactical advice during combat, XP/loot distribution
                - WORLD       — location/dungeon generation, factions, world events, timeline
                - LORE        — rules questions, lore queries, manual look-ups, general GM assistance
                
                Respond with ONLY the intent name (one word, uppercase). No explanation.
                """;

        String response = client.prompt()
                .system(systemPrompt)
                .user(u -> u.text("{p}").param("p", userMessage))
                .call().content();

        if (response == null) return AgentIntent.LORE;
        String clean = response.trim().toUpperCase();
        try {
            return AgentIntent.valueOf(clean);
        } catch (IllegalArgumentException e) {
            log.debug("[Orchestrator] Unrecognised intent '{}', defaulting to LORE", clean);
            return AgentIntent.LORE;
        }
    }

    /**
     * Fast heuristic classification based on keyword matching — no LLM cost.
     */
    public AgentIntent classifyIntentHeuristic(String userMessage) {
        String lower = userMessage.toLowerCase();
        if (lower.contains("character") || lower.contains("personaje") || lower.contains("npc"))
            return AgentIntent.CHARACTER;
        if (lower.contains("item") || lower.contains("weapon") || lower.contains("armor") || lower.contains("objeto"))
            return AgentIntent.ITEM;
        if (lower.contains("combat") || lower.contains("encounter") || lower.contains("fight") || lower.contains("battle"))
            return AgentIntent.COMBAT;
        if (lower.contains("location") || lower.contains("dungeon") || lower.contains("world") || lower.contains("faction"))
            return AgentIntent.WORLD;
        return AgentIntent.LORE;
    }

    /**
     * Route a GameMasterRequest to the appropriate agent based on classified intent.
     * This is the Phase 2+ unified orchestration entry point.
     */
    public GameMasterResponse orchestrate(GameMasterRequest request) {
        AgentIntent intent = classifyIntent(request.getPrompt());
        log.info("[Orchestrator] Routing '{}' → {}", request.getPrompt(), intent);

        return switch (intent) {
            case LORE -> {
                String answer = loreAgent.solveDoubt(
                        request.getPrompt(),
                        request.getFoundrySystem() != null ? request.getFoundrySystem() : "unknown",
                        request.getConversationId());
                GameMasterResponse r = new GameMasterResponse();
                r.setNarration(answer);
                r.setActions(java.util.List.of());
                yield r;
            }
            case COMBAT -> {
                CombatAdviceRequest adviceRequest = new CombatAdviceRequest();
                adviceRequest.setPrompt(request.getPrompt());
                adviceRequest.setWorldId(request.getWorldId());
                adviceRequest.setSystemId(request.getFoundrySystem());
                adviceRequest.setConversationId(request.getConversationId());
                adviceRequest.setWorldState(request.getWorldState());
                adviceRequest.setAbilities(request.getAbilities());
                if (request.getTokenId() != null) {
                    adviceRequest.setActiveTokenId(request.getTokenId());
                    adviceRequest.setActiveTokenName(request.getTokenName());
                }
                CombatAdviceResponse advice = combatAgent.adviseAction(adviceRequest);
                GameMasterResponse r = new GameMasterResponse();
                r.setNarration(advice.getNarration());
                r.setActions(advice.getActions());
                r.setSelectedAbilityId(advice.getSelectedAbilityId());
                r.setSelectedAbilityName(advice.getSelectedAbilityName());
                r.setReasoning(advice.getReasoning());
                yield r;
            }
            default -> {
                // CHARACTER, ITEM, WORLD → fall back to session GM for general response
                yield sessionChatAgent.processRequest(request);
            }
        };
    }

    public enum AgentIntent {
        CHARACTER, ITEM, LORE, COMBAT, WORLD
    }
}

