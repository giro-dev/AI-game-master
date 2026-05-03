package dev.agiro.masterserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.DirectorRequest;
import dev.agiro.masterserver.dto.DirectorResponse;
import dev.agiro.masterserver.dto.IntentClassification;
import dev.agiro.masterserver.dto.NpcDialogueDto;
import dev.agiro.masterserver.dto.StateUpdateDto;
import dev.agiro.masterserver.model.AdventureModule;
import dev.agiro.masterserver.model.NpcProfile;
import dev.agiro.masterserver.model.Scene;
import dev.agiro.masterserver.repository.AdventureModuleRepository;
import dev.agiro.masterserver.repository.AdventureSessionRepository;
import dev.agiro.masterserver.model.AdventureSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The brain that turns a transcribed player utterance into a director turn:
 * classifies intent, asks for confirmation when needed, prompts the LLM with
 * the current narrative state, applies persistent state updates and
 * synthesises NPC voice lines via {@link TtsService}.
 */
@Slf4j
@Service
public class AdventureDirectorService {

    @Value("classpath:/prompts/adventure_director_system.txt")
    private Resource directorSystemPrompt;

    @Value("${adventure.max-tension-level:10}")
    private int maxTensionLevel;

    private static final String USER_TEMPLATE = """
            JUGADOR: {playerName}
            ACCIÓ DECLARADA / FRASE: {transcription}
            INTENT CLASSIFICAT: {intent} (confiança {confidence})

            ESTAT DEL MÓN (Foundry):
            {worldState}

            DECISIONS RECENTS DEL JUGADOR:
            {recentDecisions}

            Genera la teva resposta en JSON tal com s'especifica.
            """;

    private final AdventureModuleRepository adventureModuleRepository;
    private final AdventureSessionRepository adventureSessionRepository;
    private final IntentClassifierService intentClassifier;
    private final TtsService ttsService;
    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatMemory chatMemory;
    private final ObjectMapper objectMapper;

    /** Pending unconfirmed turns, keyed by adventure session id. */
    private final Map<String, IntentClassification> pendingIntents = new ConcurrentHashMap<>();
    private final Map<String, String> pendingTranscriptions = new ConcurrentHashMap<>();

    public AdventureDirectorService(AdventureModuleRepository adventureModuleRepository,
                                    AdventureSessionRepository adventureSessionRepository,
                                    IntentClassifierService intentClassifier,
                                    TtsService ttsService,
                                    ChatClient.Builder chatClientBuilder,
                                    VectorStore vectorStore,
                                    ChatMemory chatMemory,
                                    ObjectMapper objectMapper,
                                    @Value("${game-master.chat.default-model:gpt-4.1-mini}") String defaultModel) {
        this.adventureModuleRepository = adventureModuleRepository;
        this.adventureSessionRepository = adventureSessionRepository;
        this.intentClassifier = intentClassifier;
        this.ttsService = ttsService;
        this.vectorStore = vectorStore;
        this.chatMemory = chatMemory;
        this.objectMapper = objectMapper;
        this.chatClient = chatClientBuilder
                .defaultOptions(ChatOptions.builder()
                        .model(defaultModel)
                        .temperature(0.7)
                        .build())
                .build();
    }

    public DirectorResponse process(DirectorRequest request) {
        if (request.getAdventureSessionId() == null) {
            return errorResponse("adventureSessionId is required");
        }

        AdventureSession session = adventureSessionRepository.findById(request.getAdventureSessionId())
                .orElse(null);
        if (session == null) {
            return errorResponse("Adventure session not found: " + request.getAdventureSessionId());
        }
        AdventureModule module = adventureModuleRepository.findById(session.getAdventureModuleId())
                .orElse(null);
        if (module == null) {
            return errorResponse("Adventure module not found for session " + session.getId());
        }

        // 1. Confirmation handling: if a confirmationResponse is supplied, resolve any pending intent.
        if (request.getConfirmationResponse() != null) {
            return resumeFromConfirmation(request, session, module);
        }

        // 2. Classify the new utterance.
        Scene currentScene = findScene(module, session.getCurrentSceneId()).orElse(null);
        String sceneContext = buildSceneContextForClassifier(module, currentScene);
        IntentClassification classification = intentClassifier.classify(request.getTranscription(), sceneContext);

        if (classification.isRequiresConfirmation()) {
            pendingIntents.put(session.getId(), classification);
            pendingTranscriptions.put(session.getId(), request.getTranscription());
            return DirectorResponse.builder()
                    .confirmationNeeded(true)
                    .confirmationQuestion("Vols dir que " + classification.getSummary() + "?")
                    .reasoning("Intent " + classification.getIntent() + " amb confiança " + classification.getConfidence())
                    .build();
        }

        return runDirectorTurn(request.getTranscription(), classification, request, session, module, currentScene);
    }

    /* ------------------------------------------------------------------ */
    /*  Confirmation handling                                              */
    /* ------------------------------------------------------------------ */

    private DirectorResponse resumeFromConfirmation(DirectorRequest request, AdventureSession session, AdventureModule module) {
        String reply = request.getConfirmationResponse() == null ? "" : request.getConfirmationResponse().trim().toLowerCase();
        IntentClassification pending = pendingIntents.remove(session.getId());
        String pendingTranscription = pendingTranscriptions.remove(session.getId());

        if (pending == null || pendingTranscription == null) {
            return errorResponse("No pending intent to confirm for session " + session.getId());
        }

        if (reply.startsWith("y") || "si".equals(reply) || "sí".equals(reply) || "true".equals(reply)) {
            // Player confirmed → run the director turn with the original utterance.
            pending.setRequiresConfirmation(false);
            Scene currentScene = findScene(module, session.getCurrentSceneId()).orElse(null);
            return runDirectorTurn(pendingTranscription, pending, request, session, module, currentScene);
        }
        if (reply.startsWith("n") || "false".equals(reply)) {
            return DirectorResponse.builder()
                    .narration("D'acord, descartem aquesta acció. Què vols fer?")
                    .reasoning("Intent rejected by player")
                    .build();
        }
        // Anything else (e.g. "rephrase") → ask the player to restate.
        return DirectorResponse.builder()
                .narration("Reformula la teva acció, si us plau.")
                .reasoning("Intent rephrase requested")
                .build();
    }

    /* ------------------------------------------------------------------ */
    /*  Director turn                                                      */
    /* ------------------------------------------------------------------ */

    private DirectorResponse runDirectorTurn(String transcription,
                                             IntentClassification classification,
                                             DirectorRequest request,
                                             AdventureSession session,
                                             AdventureModule module,
                                             Scene currentScene) {

        Map<String, Object> systemParams = buildSystemPromptParams(module, session, currentScene, request.getFoundrySystem());

        QuestionAnswerAdvisor ragAdvisor = buildRagAdvisor(request);
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId("adventure-" + session.getId())
                .build();

        Map<String, Object> userParams = new HashMap<>();
        userParams.put("playerName", Optional.ofNullable(request.getPlayerName()).orElse("desconegut"));
        userParams.put("transcription", transcription);
        userParams.put("intent", classification.getIntent());
        userParams.put("confidence", String.format("%.2f", classification.getConfidence()));
        userParams.put("worldState", request.getWorldState() == null ? "(sense informació)" : request.getWorldState().toString());
        userParams.put("recentDecisions", recentDecisions(session));

        DirectorResponse aiResponse;
        try {
            aiResponse = chatClient.prompt()
                    .system(s -> s.text(directorSystemPrompt).params(systemParams))
                    .advisors(ragAdvisor, memoryAdvisor)
                    .user(u -> u.text(USER_TEMPLATE).params(userParams))
                    .call()
                    .entity(DirectorResponse.class);
        } catch (Exception e) {
            log.error("Adventure director LLM call failed", e);
            return errorResponse("Director call failed: " + e.getMessage());
        }

        if (aiResponse == null) {
            return errorResponse("Empty response from director model");
        }

        // Persist state updates.
        applyStateUpdates(session, aiResponse.getStateUpdates(), transcription);

        // Synthesise NPC dialogue audio.
        synthesiseNpcDialogues(aiResponse.getNpcDialogues(), module);

        return aiResponse;
    }

    private QuestionAnswerAdvisor buildRagAdvisor(DirectorRequest request) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op filter = b.eq("foundry_system", Optional.ofNullable(request.getFoundrySystem()).orElse("unknown"));
        if (request.getWorldId() != null) {
            filter = b.and(filter, b.eq("world_id", request.getWorldId()));
        }
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(6)
                        .filterExpression(filter.build())
                        .build())
                .build();
    }

    private Map<String, Object> buildSystemPromptParams(AdventureModule module, AdventureSession session, Scene scene, String foundrySystem) {
        String npcs = module.getNpcs().stream()
                .filter(npc -> scene == null || scene.getNpcIds().contains(npc.getId()))
                .map(this::renderNpc)
                .collect(Collectors.joining("\n"));

        String availableClues = module.getClues().stream()
                .filter(c -> !session.getDiscoveredClueIds().contains(c.getId()))
                .map(c -> "- " + c.getTitle() + " (core=" + c.isCore() + "): " + safe(c.getDescription()))
                .collect(Collectors.joining("\n"));

        String discoveredClues = module.getClues().stream()
                .filter(c -> session.getDiscoveredClueIds().contains(c.getId()))
                .map(c -> "- " + c.getTitle())
                .collect(Collectors.joining("\n"));

        Map<String, Object> params = new HashMap<>();
        params.put("system", Optional.ofNullable(module.getSystem()).orElse(safe(foundrySystem)));
        params.put("adventureTitle", safe(module.getTitle()));
        params.put("currentSceneTitle", scene != null ? safe(scene.getTitle()) : "(sense escena)");
        params.put("readAloudText", scene != null ? safe(scene.getReadAloudText()) : "");
        params.put("gmNotes", scene != null ? safe(scene.getGmNotes()) : "");
        params.put("npcsInScene", npcs.isEmpty() ? "(cap NPC present)" : npcs);
        params.put("availableClues", availableClues.isEmpty() ? "(cap)" : availableClues);
        params.put("discoveredClues", discoveredClues.isEmpty() ? "(cap encara)" : discoveredClues);
        params.put("tensionLevel", session.getTensionLevel());
        params.put("language", "Català");
        return params;
    }

    private String renderNpc(NpcProfile npc) {
        return "- " + npc.getName() + " [id=" + npc.getId() + ", veu=" + safe(npc.getVoiceId())
                + ", actitud=" + safe(npc.getCurrentDisposition()) + "]: "
                + safe(npc.getPersonality());
    }

    private String recentDecisions(AdventureSession session) {
        List<String> log = session.getPlayerDecisionLog();
        if (log == null || log.isEmpty()) return "(cap)";
        int from = Math.max(0, log.size() - 10);
        return String.join("\n- ", log.subList(from, log.size()));
    }

    private String buildSceneContextForClassifier(AdventureModule module, Scene scene) {
        if (scene == null) return "Aventura: " + safe(module.getTitle());
        return "Aventura: " + safe(module.getTitle())
                + "\nEscena: " + safe(scene.getTitle())
                + "\n" + safe(scene.getReadAloudText());
    }

    private void applyStateUpdates(AdventureSession session, StateUpdateDto updates, String transcription) {
        if (updates != null) {
            if (updates.getDiscoveredClues() != null) {
                session.getDiscoveredClueIds().addAll(updates.getDiscoveredClues());
            }
            if (updates.getNpcDispositionChanges() != null) {
                session.getNpcDispositions().putAll(updates.getNpcDispositionChanges());
            }
            if (updates.getTransitionTriggered() != null && !updates.getTransitionTriggered().isBlank()) {
                session.setCurrentSceneId(updates.getTransitionTriggered());
            }
            int delta = updates.getTensionDelta();
            int newTension = Math.max(0, Math.min(maxTensionLevel, session.getTensionLevel() + delta));
            session.setTensionLevel(newTension);
        }
        if (transcription != null && !transcription.isBlank()) {
            session.getPlayerDecisionLog().add(transcription);
        }
        adventureSessionRepository.save(session);
    }

    private void synthesiseNpcDialogues(List<NpcDialogueDto> dialogues, AdventureModule module) {
        if (dialogues == null || dialogues.isEmpty()) return;
        Map<String, NpcProfile> byId = module.getNpcs().stream()
                .collect(Collectors.toMap(NpcProfile::getId, n -> n, (a, b) -> a));
        for (NpcDialogueDto d : dialogues) {
            NpcProfile npc = byId.get(d.getNpcId());
            String voice = d.getVoiceId() != null && !d.getVoiceId().isBlank()
                    ? d.getVoiceId()
                    : (npc != null ? npc.getVoiceId() : null);
            float pitch = npc != null && npc.getVoicePitch() != null ? npc.getVoicePitch() : 1.0f;
            float speed = npc != null && npc.getVoiceSpeed() != null ? npc.getVoiceSpeed() : 1.0f;
            try {
                byte[] wav = ttsService.synthesize(d.getText(), voice, pitch, speed);
                if (wav != null && wav.length > 0) {
                    d.setAudioBase64(Base64.getEncoder().encodeToString(wav));
                }
                d.setVoiceId(voice);
                if (d.getNpcName() == null && npc != null) {
                    d.setNpcName(npc.getName());
                }
            } catch (Exception e) {
                log.warn("TTS synthesis failed for NPC '{}': {}", d.getNpcId(), e.getMessage());
            }
        }
    }

    private Optional<Scene> findScene(AdventureModule module, String sceneId) {
        if (sceneId == null) return Optional.empty();
        return module.getActs().stream()
                .flatMap(a -> a.getScenes().stream())
                .filter(s -> sceneId.equals(s.getId()))
                .findFirst();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static DirectorResponse errorResponse(String message) {
        return DirectorResponse.builder()
                .narration(message)
                .reasoning(message)
                .npcDialogues(Collections.emptyList())
                .build();
    }
}
