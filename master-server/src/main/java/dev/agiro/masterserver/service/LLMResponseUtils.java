package dev.agiro.masterserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared utility methods for cleaning and parsing LLM responses.
 * Centralises logic previously duplicated across ConceptAgent,
 * FieldFillerAgent, ItemGenerationAgent and CharacterGenerationService.
 */
@Slf4j
public final class LLMResponseUtils {

    private LLMResponseUtils() {}

    private static final Pattern MARKDOWN_FENCE = Pattern.compile(
            "(?s)^\\s*```\\w*\\s*|\\s*```\\s*$"
    );

    /**
     * Strip markdown code fences and trim whitespace.
     *
     * @param response   raw LLM text
     * @param fallback   value returned when {@code response} is null/blank
     *                   (use "{}" for objects, "[]" for arrays)
     */
    public static String cleanJsonResponse(String response, String fallback) {
        if (response == null || response.isBlank()) return fallback;
        return MARKDOWN_FENCE.matcher(response).replaceAll("").trim();
    }

    /** Convenience overload – defaults to empty object. */
    public static String cleanJsonResponse(String response) {
        return cleanJsonResponse(response, "{}");
    }

    /**
     * Extract a character name from the AI's core concept map.
     * Tries well-known keys, then case-insensitive search, then first short string.
     */
    public static String extractName(Map<String, Object> coreConcept) {
        List<String> candidateKeys = List.of(
                "name", "nombre", "nom", "nome", "Name",
                "character_name", "characterName",
                "actor_name", "actorName"
        );
        for (String key : candidateKeys) {
            Object val = coreConcept.get(key);
            if (val instanceof String s && !s.isBlank()) {
                return s;
            }
        }

        for (Map.Entry<String, Object> entry : coreConcept.entrySet()) {
            if (entry.getKey().toLowerCase().contains("name") || entry.getKey().toLowerCase().contains("nom")) {
                if (entry.getValue() instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        }

        for (Object val : coreConcept.values()) {
            if (val instanceof String s && !s.isBlank() && s.length() < 60) {
                log.warn("Could not find 'name' key in core concept, using first short string: '{}'", s);
                return s;
            }
        }

        log.warn("No name found in core concept: {}", coreConcept.keySet());
        return "AI Character";
    }

    private static final int MAX_PARSE_RETRIES = 2;

    /**
     * Parse JSON from an LLM response with automatic retry.
     * On failure, re-invokes the LLM with the malformed response asking for a fix.
     *
     * @param rawResponse  The raw LLM response text
     * @param fallback     Fallback value for cleanJsonResponse
     * @param targetType   The Java class to deserialize into
     * @param chatClient   ChatClient for retries
     * @param objectMapper Jackson mapper
     * @param systemPrompt Original system prompt (for retry context)
     * @param userPrompt   Original user prompt (for retry context)
     * @return Parsed object of type T
     */
    @SuppressWarnings("unchecked")
    public static <T> T parseJsonWithRetry(
            String rawResponse,
            String fallback,
            Class<T> targetType,
            ChatClient chatClient,
            ObjectMapper objectMapper,
            String systemPrompt,
            String userPrompt) throws Exception {

        String cleaned = cleanJsonResponse(rawResponse, fallback);

        for (int attempt = 0; attempt <= MAX_PARSE_RETRIES; attempt++) {
            try {
                return objectMapper.readValue(cleaned, targetType);
            } catch (Exception e) {
                if (attempt == MAX_PARSE_RETRIES) {
                    log.error("JSON parsing failed after {} retries. Last cleaned response: {}",
                            MAX_PARSE_RETRIES, cleaned.substring(0, Math.min(200, cleaned.length())));
                    throw e;
                }
                log.warn("JSON parse attempt {} failed ({}), requesting fix from LLM",
                        attempt + 1, e.getMessage());

                String fixPrompt = "Your previous response was not valid JSON. " +
                        "Here is what you returned:\n" + cleaned.substring(0, Math.min(500, cleaned.length())) +
                        "\n\nPlease fix the JSON and respond with ONLY valid JSON. No explanations.";

                String retryResponse = chatClient.prompt()
                        .system(systemPrompt)
                        .user(fixPrompt)
                        .call()
                        .content();

                cleaned = cleanJsonResponse(retryResponse, fallback);
            }
        }
        throw new IllegalStateException("Unreachable");
    }
}
