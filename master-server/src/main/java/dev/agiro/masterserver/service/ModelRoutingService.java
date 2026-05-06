package dev.agiro.masterserver.service;

import dev.agiro.masterserver.config.ModelRoutingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * Central routing service: maps each named operation to a model tier and provides
 * per-operation {@link ChatOptions} and a timing wrapper.
 *
 * <p>Operations defined in {@code game-master.routing.operations}:
 * <ul>
 *   <li>{@code intent-classifier}  — Tier 1: fast/cheap, structured JSON</li>
 *   <li>{@code roll-decision}      — Tier 1: fast/cheap, structured JSON</li>
 *   <li>{@code manual-answer}      — Tier 2: balanced quality</li>
 *   <li>{@code director-stateful}  — Tier 3: high quality, stateful narration</li>
 *   <li>{@code state-verifier}     — Tier 4: critical mutation validation</li>
 * </ul>
 */
@Slf4j
@Service
public class ModelRoutingService {

    private final ModelRoutingProperties properties;
    private final String defaultModel;

    public ModelRoutingService(ModelRoutingProperties properties,
                               @Value("${game-master.chat.default-model:gpt-4.1-mini}") String defaultModel) {
        this.properties = properties;
        this.defaultModel = defaultModel;
    }

    /**
     * Returns {@link ChatOptions} configured for the given operation.
     * Falls back to the global default model when routing is disabled or no profile is found.
     */
    public ChatOptions optionsFor(String operation) {
        ModelRoutingProperties.OperationProfile profile = profile(operation);
        String model = resolveModel(profile);
        ChatOptions.Builder builder = ChatOptions.builder()
                .model(model)
                .temperature(profile.getTemperature());
        if (profile.getMaxTokens() != null) {
            builder.maxTokens(profile.getMaxTokens());
        }
        return builder.build();
    }

    /** Returns the model name that will be used for {@code operation}. */
    public String modelFor(String operation) {
        return resolveModel(profile(operation));
    }

    /**
     * Executes {@code action}, logs timing and warns when the configured latency budget
     * is exceeded.
     *
     * @param operation  the operation name (used for log context and profile lookup)
     * @param sessionId  optional session/conversation id for log context
     * @param action     the supplier to execute and time
     */
    public <T> T timed(String operation, String sessionId, Supplier<T> action) {
        ModelRoutingProperties.OperationProfile profile = profile(operation);
        String model = resolveModel(profile);
        long start = System.currentTimeMillis();
        try {
            T result = action.get();
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed > profile.getLatencyBudgetMs()) {
                log.warn("[TIMING] op={} model={} session={} elapsed={}ms (budget={}ms EXCEEDED)",
                        operation, model, sessionId, elapsed, profile.getLatencyBudgetMs());
            } else {
                log.info("[TIMING] op={} model={} session={} elapsed={}ms",
                        operation, model, sessionId, elapsed);
            }
            return result;
        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[TIMING] op={} model={} session={} elapsed={}ms FAILED: {}",
                    operation, model, sessionId, elapsed, e.getMessage());
            throw e;
        }
    }

    private ModelRoutingProperties.OperationProfile profile(String operation) {
        if (!properties.isEnabled()) {
            return new ModelRoutingProperties.OperationProfile();
        }
        return properties.getProfile(operation);
    }

    private String resolveModel(ModelRoutingProperties.OperationProfile profile) {
        return StringUtils.hasText(profile.getPreferredModel()) ? profile.getPreferredModel() : defaultModel;
    }
}
