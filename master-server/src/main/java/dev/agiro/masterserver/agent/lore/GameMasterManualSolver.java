package dev.agiro.masterserver.agent.lore;

import dev.agiro.masterserver.tool.RAGService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Game Master Manual Solver — RAG-backed rules and lore Q&A.
 * <p>
 * Answers GM questions about game rules, lore, and mechanics by:
 * <ol>
 *   <li>Querying the RAG knowledge base (ingested manuals) for relevant context.</li>
 *   <li>Injecting that context into the LLM prompt as grounding material.</li>
 *   <li>Maintaining per-conversation JDBC memory so the GM can ask follow-up
 *       questions within the same session.</li>
 * </ol>
 * <p>
 * Two memory scopes are available:
 * <ul>
 *   <li><b>Per-campaign</b>: conversationId = {@code worldId} — isolated per world.</li>
 *   <li><b>Cross-campaign</b>: conversationId = {@code systemId + ":global"} — system-level
 *       knowledge shared across all campaigns for the same game system.</li>
 * </ul>
 */
@Slf4j
@Service
public class GameMasterManualSolver {

    private static final int RAG_TOP_K = 6;

    @Value("classpath:/prompts/manual_system.txt")
    private Resource manualSystemPrompt;

    private final ChatClient chatClient;
    private final RAGService ragService;
    private final ChatMemory chatMemory;

    public GameMasterManualSolver(ChatClient.Builder chatClientBuilder,
                                   RAGService ragService,
                                   ChatMemory chatMemory) {
        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder()
                        .model("gpt-4.1-mini")
                        .temperature(0.3)
                        .build())
                .build();
        this.ragService = ragService;
        this.chatMemory = chatMemory;
    }

    /**
     * Answer a rules/lore question for a given game system.
     * Uses per-campaign memory scoped to the provided {@code conversationId}.
     *
     * @param query          the GM's question
     * @param gameSystem     the Foundry VTT system ID (e.g. "dnd5e", "pf2e")
     * @param conversationId conversation/session identifier for memory scoping;
     *                       if null, falls back to a cross-campaign (system-global) scope
     * @return HTML-formatted answer with inline citations
     */
    public String solveDoubt(String query, String gameSystem, String conversationId) {
        log.info("[ManualSolver] Query='{}' system='{}' conv='{}'", query, gameSystem, conversationId);

        String scopedConversationId = resolveConversationId(conversationId, gameSystem);
        String systemPrompt = buildSystemPrompt();
        String ragContext = fetchRagContext(query, gameSystem);
        String fullPrompt = buildFullPrompt(query, ragContext);

        try {
            return chatClient.prompt()
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory)
                            .conversationId(scopedConversationId)
                            .build())
                    .system(systemPrompt)
                    .user(u -> u.text("{p}").param("p", fullPrompt))
                    .call().content();

        } catch (Exception e) {
            log.error("[ManualSolver] LLM call failed: {}", e.getMessage());
            return "<p>I encountered an error answering your question: " + e.getMessage() + "</p>";
        }
    }

    /**
     * Convenience overload — uses system-global (cross-campaign) memory when no
     * conversationId is provided. Compatible with the {@link dev.agiro.masterserver.controller.GameMasterManualController}.
     */
    public String solveDoubt(String query, String gameSystem) {
        return solveDoubt(query, gameSystem, null);
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private String buildSystemPrompt() {
        try {
            String base = manualSystemPrompt.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            // Use the configured default language; controllers can override via conversationId scope
            return base.replace("{language}", "english");
        } catch (Exception e) {
            return "You are an expert Game Master assistant for tabletop RPGs. " +
                   "Answer questions about game rules accurately. " +
                   "Format your response in HTML.";
        }
    }

    private String fetchRagContext(String query, String gameSystem) {
        if (gameSystem == null || gameSystem.isBlank()) return "";
        try {
            // Combine rules context + extracted entities for richer grounding
            String rules = ragService.searchRulesContext(query, gameSystem, RAG_TOP_K);
            String entities = ragService.searchExtractedEntities(query, gameSystem, null, null, 3);
            StringBuilder ctx = new StringBuilder();
            if (!rules.isBlank()) ctx.append(rules);
            if (!entities.isBlank()) {
                if (!ctx.isEmpty()) ctx.append("\n\n---\n\n");
                ctx.append(entities);
            }
            return ctx.toString();
        } catch (Exception e) {
            log.debug("[ManualSolver] RAG fetch failed: {}", e.getMessage());
            return "";
        }
    }

    private String buildFullPrompt(String query, String ragContext) {
        if (ragContext.isBlank()) return query;
        return "=== CONTEXT FROM MANUALS ===\n" +
               truncate(ragContext, 4000) +
               "\n\n=== QUESTION ===\n" +
               query;
    }

    /**
     * Resolve the conversation ID for memory scoping:
     * <ul>
     *   <li>If a {@code conversationId} is provided → per-campaign scope: {@code campaign:<id>}</li>
     *   <li>Otherwise → cross-campaign / system-global scope: {@code global:<systemId>}</li>
     * </ul>
     */
    private String resolveConversationId(String conversationId, String gameSystem) {
        if (conversationId != null && !conversationId.isBlank()) {
            return "campaign:" + conversationId;
        }
        return "global:" + (gameSystem != null ? gameSystem : "default");
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
