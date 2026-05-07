package dev.agiro.masterserver.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.agiro.masterserver.model.Act;
import dev.agiro.masterserver.model.AdventureModule;
import dev.agiro.masterserver.model.Clue;
import dev.agiro.masterserver.model.NpcProfile;
import dev.agiro.masterserver.model.Scene;
import dev.agiro.masterserver.model.SceneTransition;
import dev.agiro.masterserver.pdf_extractor.IngestionPipeline;
import dev.agiro.masterserver.pdf_extractor.PDFDocumentReader;
import dev.agiro.masterserver.repository.AdventureModuleRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
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
                   "gender": "male | female | unknown",
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
            - Indica el sexe narratiu de cada NPC com "male", "female" o "unknown" segons el text.
            - Suggereix una veu (voiceSuggestion) per cada NPC en funció de la seva descripció.
            - Cada escena ha de tenir un orderIndex únic dins del seu acte.
            - Si manca informació, omple els camps amb un valor raonable; mai tornis null.
            - NO afegeixis cap text fora del JSON. Sense markdown.
            """;

    private static final int MAX_PROMPT_CHARS = 60_000;

    private final ChatClient chatClient;
    private final AdventureModuleRepository adventureModuleRepository;
    private final PDFDocumentReader pdfDocumentReader;
    private final IngestionPipeline ingestionPipeline;
    private final PiperVoiceSelector piperVoiceSelector;

    public AdventureIngestionService(ChatClient.Builder chatClientBuilder,
                                     AdventureModuleRepository adventureModuleRepository,
                                     PDFDocumentReader pdfDocumentReader,
                                     IngestionPipeline ingestionPipeline,
                                     PiperVoiceSelector piperVoiceSelector,
                                     ModelRoutingService modelRoutingService) {
        this.chatClient = chatClientBuilder
                .defaultOptions(modelRoutingService.optionsFor("adventure-ingestion"))
                .build();
        this.adventureModuleRepository = adventureModuleRepository;
        this.pdfDocumentReader = pdfDocumentReader;
        this.ingestionPipeline = ingestionPipeline;
        this.piperVoiceSelector = piperVoiceSelector;
    }

    public AdventureModule ingestPdf(MultipartFile file, String foundrySystem, String worldId, String bookTitle) throws IOException {
        byte[] bytes = file.getBytes();
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "adventure.pdf";

        // 1. Extract text via the existing PDF pipeline (re-uses the same readers as book ingestion).
        log.info("Extracting text from PDF...");
        List<Document> docs = pdfDocumentReader.getDocsFromPdfWithCatalog(file, foundrySystem);
        String fullText = docs.stream()
                .map(Document::getText)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n\n"));

        if (fullText.isBlank()) {
            throw new IOException("Could not extract any text from the PDF. The file may be image-based (scanned) or protected.");
        }

        if (fullText.length() > MAX_PROMPT_CHARS) {
            fullText = fullText.substring(0, MAX_PROMPT_CHARS);
        }

        // 2. Ask the LLM to structure it.
        AdventureStructureResponse structureResponse = chatClient.prompt()
                .system(STRUCTURE_PROMPT)
                .user(fullText)
                .call()
                .entity(AdventureStructureResponse.class);

        if (structureResponse == null) {
            throw new IOException("LLM returned no content for adventure structure");
        }

        AdventureModule module = buildAdventureModule(structureResponse, foundrySystem, worldId, bookTitle);

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

    private AdventureModule buildAdventureModule(AdventureStructureResponse r,
                                                 String fallbackSystem,
                                                 String worldId,
                                                 String fallbackTitle) {
        String moduleId = "adv-" + UUID.randomUUID();
        String title = orElse(r.getTitle(), fallbackTitle != null ? fallbackTitle : "Untitled adventure");
        String system = orElse(r.getSystem(), fallbackSystem != null ? fallbackSystem : "unknown");
        String synopsis = orElse(r.getSynopsis(), "");

        List<NpcProfile> npcs = r.getNpcs() == null ? List.of() :
                r.getNpcs().stream().map(n -> {
                    String npcId = orElse(n.getId(), "npc-" + UUID.randomUUID());
                    String name = orElse(n.getName(), npcId);
                    String personality = orElse(n.getPersonality(), "");
                    String secrets = orElse(n.getSecrets(), "");
                    String objectives = orElse(n.getObjectives(), "");
                    String voiceSuggestion = n.getVoiceSuggestion();
                    String gender = piperVoiceSelector.inferGender(n.getGender(), voiceSuggestion, name, personality, secrets, objectives);
                    String voiceId = piperVoiceSelector.selectInitialNpcVoice(gender, voiceSuggestion);
                    return NpcProfile.builder()
                            .id(npcId).name(name).gender(gender)
                            .personality(personality).secrets(secrets).objectives(objectives)
                            .voiceId(voiceId).voicePitch(1.0f).voiceSpeed(1.0f)
                            .currentDisposition(orElse(n.getCurrentDisposition(), "neutral"))
                            .build();
                }).collect(Collectors.toList());

        List<Clue> clues = r.getClues() == null ? List.of() :
                r.getClues().stream().map(c -> Clue.builder()
                        .id(orElse(c.getId(), "clue-" + UUID.randomUUID()))
                        .title(orElse(c.getTitle(), "Pista"))
                        .description(orElse(c.getDescription(), ""))
                        .discoveryCondition(orElse(c.getDiscoveryCondition(), ""))
                        .isCore(Boolean.TRUE.equals(c.getIsCore()))
                        .build()).collect(Collectors.toList());

        List<Act> acts = r.getActs() == null ? List.of() :
                buildActs(r.getActs());

        log.info("Parsed adventure module with {} acts, {} NPCs, {} clues", acts.size(), npcs.size(), clues.size());
        return AdventureModule.builder()
                .id(moduleId).title(title).system(system).synopsis(synopsis)
                .worldId(worldId).acts(acts).npcs(npcs).clues(clues)
                .build();
    }

    private List<Act> buildActs(List<AdventureStructureResponse.ActDto> actDtos) {
        List<Act> acts = new java.util.ArrayList<>();
        int actIdx = 0;
        for (AdventureStructureResponse.ActDto a : actDtos) {
            List<Scene> scenes = a.getScenes() == null ? List.of() : buildScenes(a.getScenes());
            acts.add(Act.builder()
                    .id(orElse(a.getId(), "act-" + UUID.randomUUID()))
                    .title(orElse(a.getTitle(), "Acte " + (actIdx + 1)))
                    .description(orElse(a.getDescription(), ""))
                    .orderIndex(a.getOrderIndex() != null ? a.getOrderIndex() : actIdx)
                    .entryConditions(a.getEntryConditions() != null ? a.getEntryConditions() : List.of())
                    .scenes(scenes)
                    .build());
            actIdx++;
        }
        return acts;
    }

    private List<Scene> buildScenes(List<AdventureStructureResponse.SceneDto> sceneDtos) {
        List<Scene> scenes = new java.util.ArrayList<>();
        int sceneIdx = 0;
        for (AdventureStructureResponse.SceneDto s : sceneDtos) {
            List<SceneTransition> transitions = s.getTransitions() == null ? List.of() :
                    s.getTransitions().stream().map(t -> SceneTransition.builder()
                            .targetSceneId(t.getTargetSceneId())
                            .condition(orElse(t.getCondition(), ""))
                            .build()).collect(Collectors.toList());
            scenes.add(Scene.builder()
                    .id(orElse(s.getId(), "scene-" + UUID.randomUUID()))
                    .title(orElse(s.getTitle(), "Escena " + (sceneIdx + 1)))
                    .readAloudText(orElse(s.getReadAloudText(), ""))
                    .gmNotes(orElse(s.getGmNotes(), ""))
                    .orderIndex(s.getOrderIndex() != null ? s.getOrderIndex() : sceneIdx)
                    .clueIds(s.getClueIds() != null ? s.getClueIds() : List.of())
                    .npcIds(s.getNpcIds() != null ? s.getNpcIds() : List.of())
                    .transitions(transitions)
                    .build());
            sceneIdx++;
        }
        return scenes;
    }

    private static String orElse(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    // ── Structured output types ─────────────────────────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AdventureStructureResponse {
        private String title;
        private String system;
        private String synopsis;
        private List<ActDto> acts;
        private List<NpcDto> npcs;
        private List<ClueDto> clues;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class ActDto {
            private String id;
            private String title;
            private String description;
            private Integer orderIndex;
            private List<String> entryConditions;
            private List<SceneDto> scenes;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class SceneDto {
            private String id;
            private String title;
            private String readAloudText;
            private String gmNotes;
            private Integer orderIndex;
            private List<String> clueIds;
            private List<String> npcIds;
            private List<TransitionDto> transitions;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class TransitionDto {
            private String targetSceneId;
            private String condition;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class NpcDto {
            private String id;
            private String name;
            private String gender;
            private String personality;
            private String secrets;
            private String objectives;
            private String voiceSuggestion;
            private String currentDisposition;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class ClueDto {
            private String id;
            private String title;
            private String description;
            private String discoveryCondition;
            private Boolean isCore;
        }
    }
}
