package dev.agiro.masterserver.service;

/**
 * Abstraction over speech synthesis providers (Piper local TTS, OpenAI TTS, …).
 * Implementations are chosen via {@code tts.provider} in application properties.
 */
public interface SpeechSynthesisService {

    /**
     * Synthesize {@code text} and return audio bytes (WAV or MP3 depending on provider).
     *
     * @param voiceId  logical voice id resolved by the active implementation
     * @param pitch    pitch modifier (1.0 = neutral; may be ignored by some providers)
     * @param speed    speed modifier (1.0 = normal; 0.25–4.0 for OpenAI)
     */
    byte[] synthesize(String text, String voiceId, float pitch, float speed);

    /**
     * Synthesize with an optional per-call instructions hint (supported by OpenAI
     * {@code gpt-4o-mini-tts}; other providers fall back to {@link #synthesize}).
     */
    default byte[] synthesizeWithInstructions(String text, String voiceId,
                                               String instructions, float pitch, float speed) {
        return synthesize(text, voiceId, pitch, speed);
    }
}
