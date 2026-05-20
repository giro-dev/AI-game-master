package dev.agiro.masterserver.util;

/**
 * Shared utility for cleaning raw LLM text responses that may be wrapped in
 * Markdown code fences (```json … ``` or ``` … ```).
 *
 * <p>This replaces the duplicated {@code cleanJsonResponse} / {@code cleanJson} /
 * {@code stripMarkdownFence} helper methods that existed in several service classes.
 */
public final class JsonUtils {

    private JsonUtils() {}

    /**
     * Strip optional Markdown code fences from a raw LLM response and return the
     * trimmed inner content.
     *
     * @param raw      the raw response string, may be {@code null}
     * @param fallback the value to return when {@code raw} is {@code null} or blank
     *                 after stripping (e.g. {@code "{}"} or {@code "[]"})
     */
    public static String stripMarkdownFences(String raw, String fallback) {
        if (raw == null) return fallback;
        String s = raw.trim();
        if (s.startsWith("```json")) {
            s = s.substring(7);
        } else if (s.startsWith("```")) {
            s = s.substring(3);
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
        }
        s = s.trim();
        return s.isEmpty() ? fallback : s;
    }
}
