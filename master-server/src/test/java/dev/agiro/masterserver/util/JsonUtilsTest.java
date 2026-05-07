package dev.agiro.masterserver.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonUtilsTest {

    private static final String FALLBACK = "{}";

    @Test
    void nullInputReturnsFallback() {
        assertEquals(FALLBACK, JsonUtils.stripMarkdownFences(null, FALLBACK));
    }

    @Test
    void blankInputReturnsFallback() {
        assertEquals(FALLBACK, JsonUtils.stripMarkdownFences("   ", FALLBACK));
    }

    @Test
    void emptyFencedBlockReturnsFallback() {
        assertEquals(FALLBACK, JsonUtils.stripMarkdownFences("```json\n\n```", FALLBACK));
    }

    @Test
    void plainJsonPassesThrough() {
        String json = "{\"a\": 1}";
        assertEquals(json, JsonUtils.stripMarkdownFences(json, FALLBACK));
    }

    @Test
    void stripsJsonFenceWithNewlines() {
        String input = "```json\n{\"a\": 1}\n```";
        assertEquals("{\"a\": 1}", JsonUtils.stripMarkdownFences(input, FALLBACK));
    }

    @Test
    void stripsGenericFenceWithNewlines() {
        String input = "```\n[1, 2, 3]\n```";
        assertEquals("[1, 2, 3]", JsonUtils.stripMarkdownFences(input, FALLBACK));
    }

    @Test
    void stripsJsonFenceWithoutNewlines() {
        String input = "```json{\"a\": 1}```";
        assertEquals("{\"a\": 1}", JsonUtils.stripMarkdownFences(input, FALLBACK));
    }

    @Test
    void missingClosingFenceStillStripsOpening() {
        String input = "```json\n{\"a\": 1}";
        assertEquals("{\"a\": 1}", JsonUtils.stripMarkdownFences(input, FALLBACK));
    }

    @Test
    void missingOpeningFenceStillStripsClosing() {
        String input = "{\"a\": 1}\n```";
        assertEquals("{\"a\": 1}", JsonUtils.stripMarkdownFences(input, FALLBACK));
    }

    @Test
    void trailingWhitespaceIsTrimmed() {
        String input = "```json\n  {\"key\": \"val\"}  \n```";
        assertEquals("{\"key\": \"val\"}", JsonUtils.stripMarkdownFences(input, FALLBACK));
    }

    @Test
    void arrayJsonStripsCorrectly() {
        String input = "```json\n[{\"id\": 1}, {\"id\": 2}]\n```";
        assertEquals("[{\"id\": 1}, {\"id\": 2}]", JsonUtils.stripMarkdownFences(input, FALLBACK));
    }
}
