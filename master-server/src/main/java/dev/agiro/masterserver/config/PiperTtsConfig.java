package dev.agiro.masterserver.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Binds the {@code piper-tts.*} configuration block.
 */
@Configuration
@ConfigurationProperties(prefix = "piper-tts")
@Getter
@Setter
public class PiperTtsConfig {
    private String host = "localhost";
    private int port = 10200;
    private String defaultVoice = "ca_balear-x_low";
    private Map<String, String> voices = new HashMap<>();
}
