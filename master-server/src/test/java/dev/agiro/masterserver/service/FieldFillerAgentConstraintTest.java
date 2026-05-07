package dev.agiro.masterserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.SystemProfileDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link FieldFillerAgent#enforceConstraints} — the pure constraint-enforcement
 * logic that scales point-budget fields and clamps range-constrained values.
 * No LLM calls are made; the test instantiates the agent with mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
class FieldFillerAgentConstraintTest {

    @Mock
    ChatClient.Builder chatClientBuilder;

    @Mock
    ModelRoutingService modelRoutingService;

    @Mock
    SystemProfileService systemProfileService;

    @Mock
    SystemAwarePromptBuilder promptBuilder;

    @Mock
    GameMasterManualSolver gameMasterManualSolver;

    @Mock
    RAGService ragService;

    FieldFillerAgent agent;

    @BeforeEach
    void setUp() {
        when(modelRoutingService.optionsFor(anyString())).thenReturn(mock(ChatOptions.class));
        when(chatClientBuilder.defaultOptions(any())).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(mock(ChatClient.class));
        agent = new FieldFillerAgent(chatClientBuilder, new ObjectMapper(),
                systemProfileService, promptBuilder, gameMasterManualSolver, ragService,
                modelRoutingService);
    }

    // ─── null / empty profile ────────────────────────────────────────────

    @Test
    void nullProfileReturnsDataUnchanged() {
        Map<String, Object> data = Map.of("system.attributes.str", 5);
        Map<String, Object> result = agent.enforceConstraints(data, null, null);
        assertEquals(data, result);
    }

    @Test
    void profileWithNoConstraintsReturnsDataUnchanged() {
        SystemProfileDto profile = SystemProfileDto.builder()
                .detectedConstraints(List.of())
                .build();
        Map<String, Object> data = Map.of("system.attributes.str", 5);
        Map<String, Object> result = agent.enforceConstraints(data, null, profile);
        assertEquals(data, result);
    }

    // ─── point_budget ────────────────────────────────────────────────────

    @Test
    void pointBudgetAlreadySatisfiedIsNotModified() {
        SystemProfileDto profile = profileWithBudget("system.attributes", 15, 1, 8);

        Map<String, Object> data = new HashMap<>();
        data.put("system.attributes.str", 5);
        data.put("system.attributes.dex", 5);
        data.put("system.attributes.con", 5);

        Map<String, Object> result = agent.enforceConstraints(data, null, profile);

        assertEquals(15, sumByPrefix(result, "system.attributes"));
    }

    @Test
    void pointBudgetIsScaledToMatchRequiredTotal() {
        SystemProfileDto profile = profileWithBudget("system.attributes", 15, 1, 8);

        Map<String, Object> data = new HashMap<>();
        data.put("system.attributes.str", 3);
        data.put("system.attributes.dex", 3);
        data.put("system.attributes.con", 3); // total = 9, need 15

        Map<String, Object> result = agent.enforceConstraints(data, null, profile);

        assertEquals(15, sumByPrefix(result, "system.attributes"));
    }

    @Test
    void pointBudgetAllZerosDistributesEvenly() {
        SystemProfileDto profile = profileWithBudget("system.attributes", 15, 1, 8);

        Map<String, Object> data = new HashMap<>();
        data.put("system.attributes.str", 0);
        data.put("system.attributes.dex", 0);
        data.put("system.attributes.con", 0);

        Map<String, Object> result = agent.enforceConstraints(data, null, profile);

        assertEquals(15, sumByPrefix(result, "system.attributes"));
    }

    @Test
    void pointBudgetWithSingleFieldSetsToBudgetTotal() {
        SystemProfileDto profile = profileWithBudget("system.luck", 10, 1, 10);

        Map<String, Object> data = new HashMap<>();
        data.put("system.luck.points", 3);

        Map<String, Object> result = agent.enforceConstraints(data, null, profile);

        assertEquals(10, ((Number) result.get("system.luck.points")).intValue());
    }

    // ─── range ───────────────────────────────────────────────────────────

    @Test
    void rangeConstraintClampsAboveMax() {
        SystemProfileDto profile = profileWithRange("system.hp", 1, 20);

        Map<String, Object> data = new HashMap<>();
        data.put("system.hp", 25);

        Map<String, Object> result = agent.enforceConstraints(data, null, profile);

        assertEquals(20, ((Number) result.get("system.hp")).intValue());
    }

    @Test
    void rangeConstraintClampsBelowMin() {
        SystemProfileDto profile = profileWithRange("system.hp", 1, 20);

        Map<String, Object> data = new HashMap<>();
        data.put("system.hp", 0);

        Map<String, Object> result = agent.enforceConstraints(data, null, profile);

        assertEquals(1, ((Number) result.get("system.hp")).intValue());
    }

    @Test
    void rangeConstraintWithinBoundsIsNotModified() {
        SystemProfileDto profile = profileWithRange("system.hp", 1, 20);

        Map<String, Object> data = new HashMap<>();
        data.put("system.hp", 15);

        Map<String, Object> result = agent.enforceConstraints(data, null, profile);

        assertEquals(15, ((Number) result.get("system.hp")).intValue());
    }

    @Test
    void nonNumericFieldIsIgnoredByRangeConstraint() {
        SystemProfileDto profile = profileWithRange("system.name", 1, 20);

        Map<String, Object> data = new HashMap<>();
        data.put("system.name", "Gandalf");

        Map<String, Object> result = agent.enforceConstraints(data, null, profile);

        assertEquals("Gandalf", result.get("system.name"));
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static SystemProfileDto profileWithBudget(String fieldPath, int total, int min, int max) {
        var constraint = SystemProfileDto.DetectedConstraint.builder()
                .type("point_budget")
                .fieldPath(fieldPath)
                .description("Budget constraint for " + fieldPath)
                .parameters(Map.of("total", total, "min", min, "max", max))
                .build();
        return SystemProfileDto.builder()
                .detectedConstraints(List.of(constraint))
                .build();
    }

    private static SystemProfileDto profileWithRange(String fieldPath, int min, int max) {
        var constraint = SystemProfileDto.DetectedConstraint.builder()
                .type("range")
                .fieldPath(fieldPath)
                .description("Range constraint for " + fieldPath)
                .parameters(Map.of("min", min, "max", max))
                .build();
        return SystemProfileDto.builder()
                .detectedConstraints(List.of(constraint))
                .build();
    }

    private static int sumByPrefix(Map<String, Object> data, String prefix) {
        return data.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix) && e.getValue() instanceof Number)
                .mapToInt(e -> ((Number) e.getValue()).intValue())
                .sum();
    }
}
