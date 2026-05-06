package dev.agiro.masterserver.config;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "game-master.routing")
@Getter
@Setter
public class ModelRoutingProperties {

    /** When false, all operations fall back to the global default model. */
    private boolean enabled = true;

    /** When true, the director runs a Tier-4 state-verification pass after critical mutations. */
    private boolean stateVerificationEnabled = false;

    private Map<String, OperationProfile> operations = new HashMap<>();

    public OperationProfile getProfile(String operation) {
        return operations.getOrDefault(operation, new OperationProfile());
    }

    @Data
    public static class OperationProfile {
        /** Primary model for this operation. Falls back to global default when blank. */
        private String preferredModel;
        /** Fallback model when the primary call fails. */
        private String fallbackModel;
        private double temperature = 0.7;
        /** "json" or "text" — for documentation/logging purposes. */
        private String expectedFormat = "text";
        private Integer maxTokens;
        /** Soft latency target in ms — logged as a warning when exceeded. */
        private long latencyBudgetMs = 10_000;
    }
}
