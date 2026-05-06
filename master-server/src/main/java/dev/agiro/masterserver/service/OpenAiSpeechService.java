package dev.agiro.masterserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.config.OpenAiTtsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * TTS implementation backed by the OpenAI Audio Speech API ({@code gpt-4o-mini-tts}).
 * Active when {@code tts.provider=openai}.
 *
 * <p>Calls the OpenAI REST endpoint directly so that the {@code instructions} field
 * (supported by {@code gpt-4o-mini-tts}) can be included — Spring AI 1.1.2 does not
 * yet expose this parameter through {@code OpenAiAudioSpeechOptions}.
 */
@Slf4j
@Primary
@Service
@ConditionalOnProperty(name = "tts.provider", havingValue = "openai")
public class OpenAiSpeechService implements SpeechSynthesisService {

    private static final String SPEECH_URL = "https://api.openai.com/v1/audio/speech";

    private final OpenAiTtsConfig config;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiSpeechService(OpenAiTtsConfig config,
                                @Value("${spring.ai.openai.api-key}") String apiKey) {
        this.config = config;
        this.restClient = RestClient.builder()
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        log.info("[OpenAI TTS] provider=openai model={} narrator-voice={}",
                config.getModel(), config.getNarratorVoice());
    }

    @Override
    public byte[] synthesize(String text, String voiceId, float pitch, float speed) {
        String voice = resolveVoice(voiceId);
        String instructions = config.getInstructions().getOrDefault(voice, config.getNpcInstructions());
        return call(text, voice, instructions, speed);
    }

    @Override
    public byte[] synthesizeWithInstructions(String text, String voiceId,
                                              String instructions, float pitch, float speed) {
        if (text == null || text.isBlank()) return new byte[0];
        String voice = resolveVoice(voiceId);
        String effectiveInstructions = (instructions != null && !instructions.isBlank())
                ? instructions
                : config.getInstructions().getOrDefault(voice, config.getNpcInstructions());
        return call(text, voice, effectiveInstructions, speed);
    }

    private byte[] call(String text, String voice, String instructions, float speed) {
        if (text == null || text.isBlank()) return new byte[0];
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.getModel());
            body.put("input", text);
            body.put("voice", voice);
            body.put("response_format", "mp3");
            if (speed != 1.0f) {
                body.put("speed", (double) speed);
            }
            if (instructions != null && !instructions.isBlank()) {
                body.put("instructions", instructions);
            }

            byte[] audio = restClient.post()
                    .uri(SPEECH_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsBytes(body))
                    .retrieve()
                    .body(byte[].class);

            log.debug("[OpenAI TTS] synthesized {} chars with voice={}", text.length(), voice);
            return audio != null ? audio : new byte[0];
        } catch (Exception e) {
            log.error("[OpenAI TTS] synthesis failed voice={}: {}", voice, e.getMessage());
            return new byte[0];
        }
    }

    /**
     * Resolve a logical voice alias (e.g. {@code "narrator"}, {@code "npc_female"}) or
     * a direct OpenAI voice name to a concrete OpenAI voice name.
     */
    private String resolveVoice(String voiceId) {
        if (voiceId == null || voiceId.isBlank()) {
            return config.getNarratorVoice();
        }
        if (isOpenAiVoice(voiceId)) return voiceId;
        String mapped = config.getVoices().get(voiceId);
        if (mapped != null && !mapped.isBlank()) return mapped;
        if ("npc_male_deep".equals(voiceId)) return randomFrom(config.getMaleVoices(), "coral");
        if ("npc_female".equals(voiceId)) return randomFrom(config.getFemaleVoices(), "nova");
        if ("narrator".equals(voiceId)) return config.getNarratorVoice();
        // Piper-style voice ids (e.g. ca_ES-upc_pau-x_low) — fall back to narrator voice
        return config.getNarratorVoice();
    }

    private static boolean isOpenAiVoice(String voiceId) {
        return switch (voiceId.toLowerCase()) {
            case "alloy", "verse", "aria", "coral", "sage", "nova",
                    "echo", "fable", "onyx", "shimmer", "ballad", "ash" -> true;
            default -> false;
        };
    }

    private static String randomFrom(List<String> list, String fallback) {
        if (list == null || list.isEmpty()) return fallback;
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }
}
