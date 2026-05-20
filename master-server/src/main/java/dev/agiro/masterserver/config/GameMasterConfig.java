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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Chat {
        private String defaultModel;
        private String defaultLanguage;
    }
}
