package dev.agiro.masterserver.service;

import dev.agiro.masterserver.config.PiperTtsConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiperVoiceSelectorTest {

    @Test
    void usesCatalanNarratorVoiceForNarration() {
        PiperVoiceSelector selector = new PiperVoiceSelector(config());

        assertEquals("ca_ES-upc_ona-medium", selector.narratorVoice());
    }

    @Test
    void picksMaleNpcVoicesFromMalePool() {
        PiperVoiceSelector selector = new PiperVoiceSelector(config());

        String selected = selector.selectInitialNpcVoice("home", null);

        assertTrue(List.of("ca_ES-upc_pau-x_low", "es_ES-davefx-medium").contains(selected));
    }

    @Test
    void repinsLegacyNarratorVoiceToGenderedPool() {
        PiperVoiceSelector selector = new PiperVoiceSelector(config());

        String selected = selector.selectVoiceForNpc("male", "ca_ES-upc_ona-medium", null);

        assertTrue(List.of("ca_ES-upc_pau-x_low", "es_ES-davefx-medium").contains(selected));
    }

    @Test
    void keepsConcreteNpcVoiceOnceAssigned() {
        PiperVoiceSelector selector = new PiperVoiceSelector(config());

        String selected = selector.selectVoiceForNpc("female", "es_AR-daniela-high", null);

        assertEquals("es_AR-daniela-high", selected);
    }

    @Test
    void infersGenderFromLegacyVoiceAlias() {
        PiperVoiceSelector selector = new PiperVoiceSelector(config());

        String gender = selector.inferGender(null, "npc_male_deep", "Capità Arnau");

        assertEquals("male", gender);
    }

    private static PiperTtsConfig config() {
        PiperTtsConfig config = new PiperTtsConfig();
        config.setDefaultVoice("ca_ES-upc_ona-medium");
        config.setNarratorVoice("ca_ES-upc_ona-medium");
        config.setMaleVoices(List.of("ca_ES-upc_pau-x_low", "es_ES-davefx-medium"));
        config.setFemaleVoices(List.of("ca_ES-upc_ona-medium", "es_AR-daniela-high"));
        config.setVoices(Map.of(
                "narrator", "ca_ES-upc_ona-medium",
                "npc_male_deep", "es_ES-davefx-medium",
                "npc_female", "ca_ES-upc_ona-medium"
        ));
        return config;
    }
}
