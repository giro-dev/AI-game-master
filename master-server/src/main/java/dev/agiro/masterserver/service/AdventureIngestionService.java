package dev.agiro.masterserver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.config.PiperTtsConfig;
import dev.agiro.masterserver.model.Act;
import dev.agiro.masterserver.model.AdventureModule;
import dev.agiro.masterserver.model.Clue;
import dev.agiro.masterserver.model.NpcProfile;
import dev.agiro.masterserver.model.Scene;
import dev.agiro.masterserver.model.SceneTransition;
import dev.agiro.masterserver.pdf_extractor.IngestionPipeline;
import dev.agiro.masterserver.pdf_extractor.PDFDocumentReader;
import dev.agiro.masterserver.repository.AdventureModuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ingests an adventure PDF: extracts text, asks the LLM to structure it into
 * acts/scenes/NPCs/clues, persists the resulting {@link AdventureModule} and
 * pushes the original chunks into the RAG vector store for in-session lookups.
 */
@Slf4j
@Service
public class AdventureIngestionService {

    private static final String STRUCTURE_PROMPT = """
            Ets un assistent que extreu l'estructura d'un mòdul d'aventura per a un joc de rol.
            Et passaré el text complet d'una aventura. Respon NOMÉS amb un JSON vàlid amb la forma:

            {
              "title": "...",
              "system": "CoC7 | dnd5e | ...",
              "synopsis": "...",
              "acts": [
                {
                  "title": "Acte 1",
                  "description": "...",
                  "orderIndex": 0,
                  "entryConditions": ["..."],
                  "scenes": [
                    {
                      "title": "Escena 1",
                      "readAloudText": "Text per llegir als jugadors",
                      "gmNotes": "Notes per al GM",
                      "orderIndex": 0,
                      "clueIds": ["clue-XYZ"],
                      "npcIds": ["npc-XYZ"],
                      "transitions": [{"targetSceneId": "scene-XYZ", "condition": "..."}]
                    }
                  ]
                }
              ],
              "npcs": [
                {
                  "id": "npc-XYZ",
                  "name": "...",
                  "personality": "...",
                  "secrets": "...",
                  "objectives": "...",
                  "voiceSuggestion": "narrator | npc_male_deep | npc_female | npc_english",
                  "currentDisposition": "neutral"
                }
              ],
              "clues": [
                {
                  "id": "clue-XYZ",
                  "title": "...",
                  "description": "...",
                  "discoveryCondition": "...",
                  "isCore": true
                }
              ]
            }

            REGLES:
            - Genera identificadors curts i únics per a NPCs i pistes (prefix "npc-" / "clue-").
            - Les pistes "core" són les imprescindibles per acabar l'aventura.
            - Suggereix una veu (voiceSuggestion) per cada NPC en funció de la seva descripció.
            - Cada escena ha de tenir un orderIndex únic dins del seu acte.
            - Si manca informació, omple els camps amb un valor raonable; mai tornis null.
            - NO afegeixis cap text fora del JSON. Sense markdown.
            """;

    private static final int MAX_PROMPT_CHARS = 60_000;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final AdventureModuleRepository adventureModuleRepository;
    private final PDFDocumentReader pdfDocumentReader;
    private final IngestionPipeline ingestionPipeline;
    private final PiperTtsConfig piperConfig;

    public AdventureIngestionService(ChatClient.Builder chatClientBuilder,
                                     ObjectMapper objectMapper,
                                     AdventureModuleRepository adventureModuleRepository,
                                     PDFDocumentReader pdfDocumentReader,
                                     IngestionPipeline ingestionPipeline,
                                     PiperTtsConfig piperConfig,
                                     @Value("${game-master.chat.default-model:gpt-4.1-mini}") String defaultModel) {
        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder()
                        .model(defaultModel)
                        .temperature(0.2)
                        .build())
                .build();
        this.objectMapper = objectMapper;
        this.adventureModuleRepository = adventureModuleRepository;
        this.pdfDocumentReader = pdfDocumentReader;
        this.ingestionPipeline = ingestionPipeline;
        this.piperConfig = piperConfig;
    }

    public AdventureModule ingestPdf(MultipartFile file, String foundrySystem, String worldId, String bookTitle) throws IOException {
        byte[] bytes = file.getBytes();
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "adventure.pdf";

        // 1. Extract text via the existing PDF pipeline (re-uses the same readers as book ingestion).
        List<Document> docs = pdfDocumentReader.getDocsFromPdfWithCatalog(file, foundrySystem);
        String fullText = docs.stream()
                .map(Document::getText)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n\n"));

        if (fullText.length() > MAX_PROMPT_CHARS) {
            fullText = fullText.substring(0, MAX_PROMPT_CHARS);
        }

        // 2. Ask the LLM to structure it.
        String aiJson = chatClient.prompt()
                .system(STRUCTURE_PROMPT)
                .user(fullText)
                .call()
                .content();
        if (aiJson == null) {
            throw new IOException("LLM returned no content for adventure structure");
        }
        aiJson = stripMarkdownFence(aiJson);

        AdventureModule module = parseAdventureModule(aiJson, foundrySystem, worldId, bookTitle);

        // 3. Persist.
        AdventureModule saved = adventureModuleRepository.save(module);
        log.info("Adventure '{}' ingested with {} acts, {} NPCs, {} clues",
                saved.getTitle(), saved.getActs().size(), saved.getNpcs().size(), saved.getClues().size());

        // 4. Also feed the RAG store so the Director can do contextual lookups later.
        try {
            ingestionPipeline.ingestAsync(bytes, fileName, worldId, foundrySystem,
                    bookTitle != null ? bookTitle : module.getTitle(), null);
        } catch (Exception e) {
            log.warn("RAG ingestion of adventure '{}' failed (continuing): {}", saved.getTitle(), e.getMessage());
        }

        return saved;
    }

    private AdventureModule parseAdventureModule(String aiJson, String fallbackSystem, String worldId, String fallbackTitle) throws IOException {
        JsonNode root = objectMapper.readTree(aiJson);

        String moduleId = "adv-" + UUID.randomUUID();
        String title = textOr(root.get("title"), fallbackTitle != null ? fallbackTitle : "Untitled adventure");
        String system = textOr(root.get("system"), fallbackSystem != null ? fallbackSystem : "unknown");
        String synopsis = textOr(root.get("synopsis"), "");

        List<NpcProfile> npcs = new ArrayList<>();
        if (root.has("npcs") && root.get("npcs").isArray()) {
            for (JsonNode n : root.get("npcs")) {
                String npcId = textOr(n.get("id"), "npc-" + UUID.randomUUID());
                String voiceId = resolveVoiceSuggestion(textOr(n.get("voiceSuggestion"), null));
                npcs.add(NpcProfile.builder()
                        .id(npcId)
                        .name(textOr(n.get("name"), npcId))
                        .personality(textOr(n.get("personality"), ""))
                        .secrets(textOr(n.get("secrets"), ""))
                        .objectives(textOr(n.get("objectives"), ""))
                        .voiceId(voiceId)
                        .voicePitch(1.0f)
                        .voiceSpeed(1.0f)
                        .currentDisposition(textOr(n.get("currentDisposition"), "neutral"))
                        .build());
            }
        }

        List<Clue> clues = new ArrayList<>();
        if (root.has("clues") && root.get("clues").isArray()) {
            for (JsonNode c : root.get("clues")) {
                clues.add(Clue.builder()
                        .id(textOr(c.get("id"), "clue-" + UUID.randomUUID()))
                        .title(textOr(c.get("title"), "Pista"))
                        .description(textOr(c.get("description"), ""))
                        .discoveryCondition(textOr(c.get("discoveryCondition"), ""))
                        .isCore(c.has("isCore") && c.get("isCore").asBoolean(false))
                        .build());
            }
        }

        List<Act> acts = new ArrayList<>();
        if (root.has("acts") && root.get("acts").isArray()) {
            int actIdx = 0;
            for (JsonNode a : root.get("acts")) {
                List<Scene> scenes = new ArrayList<>();
                if (a.has("scenes") && a.get("scenes").isArray()) {
                    int sceneIdx = 0;
                    for (JsonNode s : a.get("scenes")) {
                        List<SceneTransition> transitions = new ArrayList<>();
                        if (s.has("transitions") && s.get("transitions").isArray()) {
                            for (JsonNode t : s.get("transitions")) {
                                transitions.add(SceneTransition.builder()
                                        .targetSceneId(textOr(t.get("targetSceneId"), null))
                                        .condition(textOr(t.get("condition"), ""))
                                        .build());
                            }
                        }
                        scenes.add(Scene.builder()
                                .id(textOr(s.get("id"), "scene-" + UUID.randomUUID()))
                                .title(textOr(s.get("title"), "Escena " + (sceneIdx + 1)))
                                .readAloudText(textOr(s.get("readAloudText"), ""))
                                .gmNotes(textOr(s.get("gmNotes"), ""))
                                .orderIndex(s.has("orderIndex") ? s.get("orderIndex").asInt(sceneIdx) : sceneIdx)
                                .clueIds(stringList(s.get("clueIds")))
                                .npcIds(stringList(s.get("npcIds")))
                                .transitions(transitions)
                                .build());
                        sceneIdx++;
                    }
                }
                acts.add(Act.builder()
                        .id(textOr(a.get("id"), "act-" + UUID.randomUUID()))
                        .title(textOr(a.get("title"), "Acte " + (actIdx + 1)))
                        .description(textOr(a.get("description"), ""))
                        .orderIndex(a.has("orderIndex") ? a.get("orderIndex").asInt(actIdx) : actIdx)
                        .entryConditions(stringList(a.get("entryConditions")))
                        .scenes(scenes)
                        .build());
                actIdx++;
            }
        }

        return AdventureModule.builder()
                .id(moduleId)
                .title(title)
                .system(system)
                .synopsis(synopsis)
                .worldId(worldId)
                .acts(acts)
                .npcs(npcs)
                .clues(clues)
                .build();
    }

    private String resolveVoiceSuggestion(String suggestion) {
        if (suggestion == null || suggestion.isBlank()) return piperConfig.getDefaultVoice();
        Map<String, String> voices = piperConfig.getVoices();
        if (voices != null && voices.containsKey(suggestion)) {
            return suggestion; // logical id, resolved at synthesis time
        }
        return piperConfig.getDefaultVoice();
    }

    private static String textOr(JsonNode node, String fallback) {
        if (node == null || node.isNull()) return fallback;
        String s = node.asText("");
        return s.isEmpty() ? fallback : s;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> {
                if (n != null && !n.isNull()) out.add(n.asText(""));
            });
        }
        return out;
    }

    private static String stripMarkdownFence(String s) {
        if (s == null) return "{}";
        String trimmed = s.trim();
        if (trimmed.startsWith("```")) {
            int newline = trimmed.indexOf('\n');
            if (newline > -1) trimmed = trimmed.substring(newline + 1);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }
}
