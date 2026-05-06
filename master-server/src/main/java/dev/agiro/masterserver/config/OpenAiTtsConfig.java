package dev.agiro.masterserver.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds the {@code openai-tts.*} configuration block used by
 * {@link dev.agiro.masterserver.service.OpenAiSpeechService}.
 */
@Configuration
@ConfigurationProperties(prefix = "openai-tts")
@Getter
@Setter
public class OpenAiTtsConfig {

    /** TTS model name. Use {@code gpt-4o-mini-tts} for instruction support. */
    private String model = "gpt-4o-mini-tts";

    /** Voice for the narrator/GM descriptions. */
    private String narratorVoice = "verse";

    /** Ordered list of voices preferred for male NPCs (picked randomly). */
    private List<String> maleVoices = List.of("coral", "alloy");

    /** Ordered list of voices preferred for female NPCs (picked randomly). */
    private List<String> femaleVoices = List.of("nova", "aria");

    /**
     * Named voice aliases (same structure as {@code piper-tts.voices}).
     * E.g. {@code narrator: verse}, {@code npc_male_deep: coral}.
     */
    private Map<String, String> voices = new HashMap<>();

    /**
     * Per-voice tone instructions forwarded to the TTS model.
     * Key = OpenAI voice name (e.g. {@code verse}, {@code coral}).
     */
    private Map<String, String> instructions = new HashMap<>();

    /** Default instructions applied when no per-voice entry is configured for the narrator. */
    private String narratorInstructions =
            "You are the Game Master narrator of a role-playing adventure. " +
            "Speak with a captivating, slightly mysterious storytelling voice. " +
            "Pace for dramatic effect and vary your tone to match the mood.";

    /** Default instructions applied to NPCs when no per-voice entry is configured. */
    private String npcInstructions =
            "You are a character in a role-playing adventure. " +
            "Speak naturally and expressively to match your personality.";

    /** Maximum characters of personality to include in per-NPC instruction hints. */
    private int personalityHintMaxChars = 200;
}
