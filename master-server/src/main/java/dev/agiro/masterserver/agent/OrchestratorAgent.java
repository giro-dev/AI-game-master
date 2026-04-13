package dev.agiro.masterserver.agent;

import dev.agiro.masterserver.agent.character.CharacterAgent;
import dev.agiro.masterserver.agent.combat.CombatAgent;
import dev.agiro.masterserver.agent.item.ItemGenerationService;
import dev.agiro.masterserver.agent.lore.GameMasterManualSolver;
import dev.agiro.masterserver.agent.lore.GameMasterService;
import dev.agiro.masterserver.agent.world.WorldAgent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrator Agent — the brain of the multi-agent system.
 * <p>
 * Routes incoming requests to specialist agents based on intent.
 * Currently acts as a registry; will evolve into an intent-classifying
 * router with dependency resolution and conflict management.
 * <p>
 * Phase 1: direct access to agents (controllers inject agents directly).
 * Phase 2+: unified /gm/orchestrate endpoint with intent classification.
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

    /**
     * Intent classification — will be LLM-powered in Phase 2.
     */
    public AgentIntent classifyIntent(String userMessage) {
        String lower = userMessage.toLowerCase();
        if (lower.contains("character") || lower.contains("personaje") || lower.contains("npc"))
            return AgentIntent.CHARACTER;
        if (lower.contains("item") || lower.contains("weapon") || lower.contains("armor") || lower.contains("objeto"))
            return AgentIntent.ITEM;
        if (lower.contains("combat") || lower.contains("encounter") || lower.contains("fight"))
            return AgentIntent.COMBAT;
        if (lower.contains("location") || lower.contains("dungeon") || lower.contains("world") || lower.contains("faction"))
            return AgentIntent.WORLD;
        return AgentIntent.LORE; // Default: answer from rules/lore
    }

    public enum AgentIntent {
        CHARACTER, ITEM, LORE, COMBAT, WORLD
    }
}

