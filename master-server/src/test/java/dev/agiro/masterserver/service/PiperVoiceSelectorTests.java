package dev.agiro.masterserver.service;

import dev.agiro.masterserver.config.PiperTtsConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiperVoiceSelectorTests {

    @Test
    void narrationUsesCatalanNarratorVoice() {
        PiperTtsConfig config = baseConfig();
        PiperVoiceSelector selector = new PiperVoiceSelector(config);

        assertEquals("ca_ES-upc_ona-medium", selector.narratorVoice());
    }

    @Test
    void npcVoiceSelectionIsStableForSameNpcAndSex() {
        PiperTtsConfig config = baseConfig();
        PiperVoiceSelector selector = new PiperVoiceSelector(config);

        String first = selector.selectVoiceForNpc("npc-keeper", "male", null);
        String second = selector.selectVoiceForNpc("npc-keeper", "male", null);

        assertEquals(first, second);
        assertTrue(config.getMaleVoices().contains(first));
    }

    @Test
    void suggestionFallbackStillMapsFemalePool() {
        PiperTtsConfig config = baseConfig();
        PiperVoiceSelector selector = new PiperVoiceSelector(config);

        String voice = selector.selectVoiceForNpc("npc-seer", null, "npc_female");

        assertTrue(config.getFemaleVoices().contains(voice));
    }

    private static PiperTtsConfig baseConfig() {
        PiperTtsConfig config = new PiperTtsConfig();
        config.setDefaultVoice("ca_ES-upc_ona-medium");
        config.setNarratorVoice("ca_ES-upc_ona-medium");
        config.setMaleVoices(List.of("ca_ES-upc_pau-x_low", "es_ES-davefx-medium"));
        config.setFemaleVoices(List.of("ca_ES-upc_ona-medium", "es_AR-daniela-high"));
        config.setVoices(Map.of("narrator", "ca_ES-upc_ona-medium"));
        return config;
    }
}
