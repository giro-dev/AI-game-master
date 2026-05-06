package dev.agiro.masterserver.service;

import dev.agiro.masterserver.config.PiperTtsConfig;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

@Service
public class PiperVoiceSelector {

    private static final String GENDER_MALE = "male";
    private static final String GENDER_FEMALE = "female";
    private static final String GENDER_UNKNOWN = "unknown";
    private static final Pattern MALE_HINTS = Pattern.compile("\\b(male|man|boy|home|hombre|noi|pare|padre|father|rei|rey|king|princep|principe|senyor|senor|sir|he|him|ell)\\b");
    private static final Pattern FEMALE_HINTS = Pattern.compile("\\b(female|woman|girl|dona|mujer|noia|mare|madre|mother|reina|queen|princesa|senyora|senora|lady|she|her|ella)\\b");

    private final PiperTtsConfig config;

    public PiperVoiceSelector(PiperTtsConfig config) {
        this.config = config;
    }

    public String narratorVoice() {
        return resolveAlias(config.getNarratorVoice());
    }

    /** Returns the human-readable language name for a given voice ID. */
    public String languageForVoice(String voiceId) {
        String resolved = resolveAlias(voiceId);
        if (resolved == null || resolved.isBlank()) return "Català";
        String prefix = resolved.split("-")[0].toLowerCase();
        return switch (prefix) {
            case "ca_es" -> "Català";
            case "es_es", "es_ar", "es_mx", "es_co", "es_cl" -> "Castellà";
            case "en_us", "en_gb", "en_au" -> "English";
            case "fr_fr", "fr_be" -> "Français";
            case "de_de" -> "Deutsch";
            case "it_it" -> "Italiano";
            case "pt_pt", "pt_br" -> "Português";
            default -> "Català";
        };
    }

    /** Returns the language for the configured narrator voice. */
    public String narratorLanguage() {
        return languageForVoice(narratorVoice());
    }

    public String resolveAlias(String voiceId) {
        if (voiceId == null || voiceId.isBlank()) {
            return configuredDefaultVoice();
        }
        String mapped = config.getVoices().get(voiceId);
        return mapped != null && !mapped.isBlank() ? mapped : voiceId;
    }

    public String normalizeGender(String rawGender) {
        if (rawGender == null || rawGender.isBlank()) {
            return GENDER_UNKNOWN;
        }
        String normalized = Normalizer.normalize(rawGender, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .trim()
                .toLowerCase();

        return switch (normalized) {
            case "male", "man", "boy", "masculi", "masculino", "masculine", "home", "hombre", "noi", "mascle", "m" ->
                    GENDER_MALE;
            case "female", "woman", "girl", "femeni", "femenino", "feminine", "dona", "mujer", "noia", "femella", "f" ->
                    GENDER_FEMALE;
            default -> GENDER_UNKNOWN;
        };
    }

    public String selectInitialNpcVoice(String gender, String preferredVoiceId) {
        return chooseConcreteVoice(normalizeGender(gender), preferredVoiceId);
    }

    public String inferGender(String explicitGender, String voiceId, String... textHints) {
        String normalized = normalizeGender(explicitGender);
        if (!GENDER_UNKNOWN.equals(normalized)) {
            return normalized;
        }

        String voiceGender = inferGenderFromVoice(voiceId);
        if (!GENDER_UNKNOWN.equals(voiceGender)) {
            return voiceGender;
        }

        String combinedHints = normalizeHintText(textHints);
        boolean male = MALE_HINTS.matcher(combinedHints).find();
        boolean female = FEMALE_HINTS.matcher(combinedHints).find();
        if (male == female) {
            return GENDER_UNKNOWN;
        }
        return male ? GENDER_MALE : GENDER_FEMALE;
    }

    public String selectVoiceForNpc(String gender, String storedVoiceId, String preferredVoiceId) {
        String normalizedGender = normalizeGender(gender);
        if (shouldKeepStoredVoice(normalizedGender, storedVoiceId)) {
            return resolveAlias(storedVoiceId);
        }
        return chooseConcreteVoice(normalizedGender, preferredVoiceId);
    }

    private boolean shouldKeepStoredVoice(String normalizedGender, String storedVoiceId) {
        if (storedVoiceId == null || storedVoiceId.isBlank()) {
            return false;
        }
        if (config.getVoices().containsKey(storedVoiceId)) {
            return false;
        }
        String resolvedVoice = resolveAlias(storedVoiceId);
        if (GENDER_MALE.equals(normalizedGender) && isDefaultOrNarratorVoice(resolvedVoice)) {
            return resolvedMaleVoices().contains(resolvedVoice);
        }
        if (GENDER_FEMALE.equals(normalizedGender) && isDefaultOrNarratorVoice(resolvedVoice)) {
            return resolvedFemaleVoices().contains(resolvedVoice);
        }
        return true;
    }

    private boolean isDefaultOrNarratorVoice(String voiceId) {
        return configuredDefaultVoice().equals(voiceId) || narratorVoice().equals(voiceId);
    }

    private String inferGenderFromVoice(String voiceId) {
        if (voiceId == null || voiceId.isBlank()) {
            return GENDER_UNKNOWN;
        }
        if ("npc_male_deep".equals(voiceId)) {
            return GENDER_MALE;
        }
        if ("npc_female".equals(voiceId)) {
            return GENDER_FEMALE;
        }
        String resolvedVoice = resolveAlias(voiceId);
        if (resolvedMaleVoices().contains(resolvedVoice)) {
            return GENDER_MALE;
        }
        if (resolvedFemaleVoices().contains(resolvedVoice)) {
            return GENDER_FEMALE;
        }
        return GENDER_UNKNOWN;
    }

    private String chooseConcreteVoice(String normalizedGender, String preferredVoiceId) {
        List<String> preferredPool = preferredPool(preferredVoiceId);
        if (!preferredPool.isEmpty()) {
            return randomVoice(preferredPool);
        }

        List<String> genderPool = switch (normalizedGender) {
            case GENDER_MALE -> resolvedMaleVoices();
            case GENDER_FEMALE -> resolvedFemaleVoices();
            default -> List.of();
        };
        if (!genderPool.isEmpty()) {
            return randomVoice(genderPool);
        }

        List<String> fallbackPool = allConfiguredNpcVoices();
        if (!fallbackPool.isEmpty()) {
            return randomVoice(fallbackPool);
        }
        return narratorVoice();
    }

    private List<String> preferredPool(String preferredVoiceId) {
        if (preferredVoiceId == null || preferredVoiceId.isBlank()) {
            return List.of();
        }
        if ("npc_male_deep".equals(preferredVoiceId)) {
            return resolvedMaleVoices();
        }
        if ("npc_female".equals(preferredVoiceId)) {
            return resolvedFemaleVoices();
        }
        if ("narrator".equals(preferredVoiceId)) {
            return List.of(narratorVoice());
        }
        return List.of(resolveAlias(preferredVoiceId));
    }

    private List<String> resolvedMaleVoices() {
        return resolveVoices(config.getMaleVoices());
    }

    private List<String> resolvedFemaleVoices() {
        return resolveVoices(config.getFemaleVoices());
    }

    private List<String> allConfiguredNpcVoices() {
        Set<String> voices = new LinkedHashSet<>(resolvedMaleVoices());
        voices.addAll(resolvedFemaleVoices());
        return new ArrayList<>(voices);
    }

    private List<String> resolveVoices(List<String> voices) {
        if (voices == null || voices.isEmpty()) {
            return List.of();
        }
        return voices.stream()
                .filter(voice -> voice != null && !voice.isBlank())
                .map(this::resolveAlias)
                .distinct()
                .toList();
    }

    private String configuredDefaultVoice() {
        String defaultVoice = config.getDefaultVoice();
        if (defaultVoice == null || defaultVoice.isBlank()) {
            return "ca_ES-upc_ona-medium";
        }
        String mapped = config.getVoices().get(defaultVoice);
        return mapped != null && !mapped.isBlank() ? mapped : defaultVoice;
    }

    private String randomVoice(List<String> voices) {
        return voices.get(ThreadLocalRandom.current().nextInt(voices.size()));
    }

    private String normalizeHintText(String... textHints) {
        if (textHints == null || textHints.length == 0) {
            return "";
        }
        String combined = String.join(" ", java.util.Arrays.stream(textHints)
                .filter(text -> text != null && !text.isBlank())
                .toList());
        return Normalizer.normalize(combined, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase();
    }
}
