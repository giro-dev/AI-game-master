package dev.agiro.masterserver.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.agiro.masterserver.dto.StateUpdateDto;
import dev.agiro.masterserver.model.AdventureModule;
import dev.agiro.masterserver.model.AdventureSession;
import dev.agiro.masterserver.model.Scene;
import dev.agiro.masterserver.model.SceneTransition;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Tier-4 critical mutation verifier.
 *
 * <p>After the main director LLM proposes state changes, this service performs a
 * lightweight validation pass to catch inconsistent clue discoveries, unjustified
 * scene transitions, or disproportionate disposition changes before they are persisted.
 */
@Slf4j
@Service
public class StateVerifierService {

    private static final String USER_TEMPLATE = """
            PLAYER ACTION: {playerAction}

            CURRENT SCENE CLUE IDS: {availableClueIds}
            CURRENT NPC IDS IN SCENE: {npcIds}
            CURRENT SCENE ID: {currentSceneId}
            NEXT SCENE IDS (reachable from current): {nextSceneIds}

            PROPOSED STATE UPDATES:
            discoveredClues: {discoveredClues}
            npcDispositionChanges: {npcDispositionChanges}
            transitionTriggered: {transitionTriggered}
            tensionDelta: {tensionDelta}

            Verify and correct if needed.
            """;

    @Value("classpath:/prompts/state_verifier_system.txt")
    private Resource systemPrompt;

    private final ChatClient chatClient;
    private final ModelRoutingService modelRoutingService;

    public StateVerifierService(ChatClient.Builder chatClientBuilder,
                                ModelRoutingService modelRoutingService) {
        this.modelRoutingService = modelRoutingService;
        this.chatClient = chatClientBuilder
                .defaultOptions(modelRoutingService.optionsFor("state-verifier"))
                .build();
    }

    /**
     * Verifies the proposed {@link StateUpdateDto}. If any mutations are found inconsistent
     * a corrected DTO is returned; otherwise the original is returned unchanged.
     */
    public StateUpdateDto verify(String playerAction,
                                 AdventureSession session,
                                 AdventureModule module,
                                 Scene currentScene,
                                 StateUpdateDto proposed) {
        if (proposed == null) return null;

        String availableClueIds = currentScene == null ? "(unknown)" :
                currentScene.getClueIds().stream()
                        .filter(Objects::nonNull)
                        .filter(clueId -> !session.getDiscoveredClueIds().contains(clueId))
                        .collect(Collectors.joining(", "));

        String npcIds = currentScene == null ? "(unknown)" :
                String.join(", ", currentScene.getNpcIds());

        String nextSceneIds = currentScene == null ? "(unknown)" :
                currentScene.getTransitions().stream()
                        .map(SceneTransition::getTargetSceneId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining(", "));

        try {
            VerificationResult result = modelRoutingService.timed("state-verifier", session.getId(), () ->
                    chatClient.prompt()
                            .system(s -> s.text(systemPrompt))
                            .user(u -> u.text(USER_TEMPLATE)
                                    .param("playerAction", playerAction == null ? "" : playerAction)
                                    .param("availableClueIds", availableClueIds)
                                    .param("npcIds", npcIds)
                                    .param("currentSceneId", currentScene != null ? currentScene.getId() : "(none)")
                                    .param("nextSceneIds", nextSceneIds)
                                    .param("discoveredClues", proposed.getDiscoveredClues() == null ? "[]" : proposed.getDiscoveredClues().toString())
                                    .param("npcDispositionChanges", proposed.getNpcDispositionChanges() == null ? "{}" : proposed.getNpcDispositionChanges().toString())
                                    .param("transitionTriggered", proposed.getTransitionTriggered() == null ? "null" : proposed.getTransitionTriggered())
                                    .param("tensionDelta", String.valueOf(proposed.getTensionDelta())))
                            .call()
                            .entity(VerificationResult.class)
            );

            if (result == null || result.isValid()) {
                return proposed;
            }

            log.warn("[STATE-VERIFIER] Issues found for session {}: {}", session.getId(), result.getIssues());

            if (result.getCorrections() == null) {
                return proposed;
            }

            return StateUpdateDto.builder()
                    .discoveredClues(result.getCorrections().getDiscoveredClues() != null
                            ? result.getCorrections().getDiscoveredClues()
                            : proposed.getDiscoveredClues())
                    .npcDispositionChanges(result.getCorrections().getNpcDispositionChanges() != null
                            ? result.getCorrections().getNpcDispositionChanges()
                            : proposed.getNpcDispositionChanges())
                    .transitionTriggered(result.getCorrections().getTransitionTriggered() != null
                            ? result.getCorrections().getTransitionTriggered()
                            : proposed.getTransitionTriggered())
                    .tensionDelta(result.getCorrections().getTensionDelta())
                    .build();

        } catch (Exception e) {
            log.warn("[STATE-VERIFIER] Verification failed for session {}: {}", session.getId(), e.getMessage());
            return proposed;
        }
    }

    public boolean hasCriticalMutations(StateUpdateDto updates) {
        if (updates == null) return false;
        return (updates.getTransitionTriggered() != null && !updates.getTransitionTriggered().isBlank())
                || (updates.getDiscoveredClues() != null && !updates.getDiscoveredClues().isEmpty())
                || (updates.getNpcDispositionChanges() != null && !updates.getNpcDispositionChanges().isEmpty());
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VerificationResult {
        private boolean valid;
        private List<String> issues = new ArrayList<>();
        private StateUpdateDto corrections;
    }
}
