package dev.agiro.masterserver.config;

import lombok.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "game-master")
@Getter
@Setter
public class GameMasterConfig {
    private String defaultSystemPrompt;
    private Chat chat;
    private Ingestion ingestion;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Chat {
        private String defaultModel;
        private String defaultLanguage;

        public String getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Ingestion {
        private boolean enableRateLimit = true;
        private int rpmLimit = 90;
        private int tpmLimit = 38000;
        private int maxRetries = 5;
        private int chunkSize = 2000;
        private int minChunkSizeChars = 300;
        private int minChunkLengthToEmbed = 50;
        private int windowMinutes = 1;
    }
}
