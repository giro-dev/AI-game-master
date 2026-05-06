package dev.agiro.masterserver.service;

import dev.agiro.masterserver.dto.AdventureSceneDto;
import dev.agiro.masterserver.dto.AdventureSessionListItemDto;
import dev.agiro.masterserver.dto.AdventureSessionStateDto;
import dev.agiro.masterserver.model.AdventureModule;
import dev.agiro.masterserver.model.AdventureSession;
import dev.agiro.masterserver.model.Clue;
import dev.agiro.masterserver.model.NpcProfile;
import dev.agiro.masterserver.model.Scene;
import dev.agiro.masterserver.repository.AdventureSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdventureSessionService {

    private static final DateTimeFormatter SESSION_NAME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

    private final AdventureSessionRepository adventureSessionRepository;

    public AdventureSession createSession(AdventureModule module, String worldId) {
        String firstActId = module.getActs().isEmpty() ? null : module.getActs().get(0).getId();
        String firstSceneId = (firstActId == null || module.getActs().get(0).getScenes().isEmpty())
                ? null
                : module.getActs().get(0).getScenes().get(0).getId();

        AdventureSession session = AdventureSession.builder()
                .id("sess-" + UUID.randomUUID())
                .adventureModuleId(module.getId())
                .worldId(worldId != null ? worldId : module.getWorldId())
                .currentActId(firstActId)
                .currentSceneId(firstSceneId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        refreshDerivedFields(session, module, null);
        return adventureSessionRepository.save(session);
    }

    public List<AdventureSessionListItemDto> listSessions(String worldId, String adventureId, AdventureModule module) {
        return adventureSessionRepository.findByWorldIdAndAdventureModuleIdOrderByUpdatedAtDesc(worldId, adventureId).stream()
                .sorted(Comparator.comparing(AdventureSession::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(session -> {
                    refreshDerivedFields(session, module, null);
                    return AdventureSessionListItemDto.builder()
                            .id(session.getId())
                            .sessionName(session.getSessionName())
                            .participantNames(sortedStrings(session.getParticipantNames()))
                            .sessionSummary(session.getSessionSummary())
                            .currentSceneId(session.getCurrentSceneId())
                            .currentSceneTitle(findScene(module, session.getCurrentSceneId()).map(Scene::getTitle).orElse(null))
                            .tensionLevel(session.getTensionLevel())
                            .createdAt(session.getCreatedAt())
                            .updatedAt(session.getUpdatedAt())
                            .build();
                })
                .toList();
    }

    public AdventureSessionStateDto toStateDto(AdventureSession session, AdventureModule module) {
        refreshDerivedFields(session, module, null);
        Scene scene = findScene(module, session.getCurrentSceneId()).orElse(null);

        return AdventureSessionStateDto.builder()
                .sessionId(session.getId())
                .adventureId(module.getId())
                .sessionName(session.getSessionName())
                .participantNames(sortedStrings(session.getParticipantNames()))
                .sessionSummary(session.getSessionSummary())
                .currentActId(session.getCurrentActId())
                .currentSceneId(session.getCurrentSceneId())
                .currentScene(scene == null ? null : AdventureSceneDto.builder()
                        .title(scene.getTitle())
                        .readAloudText(scene.getReadAloudText() == null ? "" : scene.getReadAloudText())
                        .build())
                .discoveredClues(resolveClueTitles(module, session.getDiscoveredClueIds()))
                .metNpcs(resolveNpcNames(module, session.getMetNpcIds()))
                .tensionLevel(session.getTensionLevel())
                .build();
    }

    public boolean registerParticipant(AdventureSession session, String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        return session.getParticipantNames().add(playerName.trim());
    }

    public void refreshDerivedFields(AdventureSession session, AdventureModule module, String latestNarration) {
        if (session.getSessionName() == null || session.getSessionName().isBlank()) {
            Instant basis = session.getCreatedAt() != null ? session.getCreatedAt() : Instant.now();
            session.setSessionName(module.getTitle() + " — " + SESSION_NAME_FORMAT.format(basis));
        }
        session.setSessionSummary(buildSummary(session, module, latestNarration));
    }

    public Optional<Scene> findScene(AdventureModule module, String sceneId) {
        if (sceneId == null) return Optional.empty();
        return module.getActs().stream()
                .flatMap(act -> act.getScenes().stream())
                .filter(scene -> sceneId.equals(scene.getId()))
                .findFirst();
    }

    private String buildSummary(AdventureSession session, AdventureModule module, String latestNarration) {
        String sceneTitle = findScene(module, session.getCurrentSceneId())
                .map(Scene::getTitle)
                .orElse("escena desconeguda");

        String players = session.getParticipantNames().isEmpty()
                ? "sense jugadors registrats"
                : String.join(", ", sortedStrings(session.getParticipantNames()));

        List<String> recentDecisions = session.getPlayerDecisionLog() == null
                ? List.of()
                : session.getPlayerDecisionLog().stream()
                .filter(text -> text != null && !text.isBlank())
                .skip(Math.max(0, session.getPlayerDecisionLog().size() - 3))
                .map(String::trim)
                .toList();

        List<String> clueTitles = resolveClueTitles(module, session.getDiscoveredClueIds());
        List<String> npcNames = resolveNpcNames(module, session.getMetNpcIds());

        List<String> sentences = new ArrayList<>();
        sentences.add("Escena actual: " + sceneTitle + ".");
        sentences.add("Participants: " + players + ".");
        if (!clueTitles.isEmpty()) {
            sentences.add("Pistes descobertes: " + joinLimited(clueTitles, 3) + ".");
        }
        if (!npcNames.isEmpty()) {
            sentences.add("NPCs trobats: " + joinLimited(npcNames, 3) + ".");
        }
        if (latestNarration != null && !latestNarration.isBlank()) {
            sentences.add("Últim desenvolupament: " + truncate(latestNarration.trim(), 220) + ".");
        } else if (!recentDecisions.isEmpty()) {
            sentences.add("Darreres accions: " + joinLimited(recentDecisions, 2) + ".");
        }
        return String.join(" ", sentences);
    }

    private List<String> resolveClueTitles(AdventureModule module, Set<String> clueIds) {
        if (clueIds == null || clueIds.isEmpty()) return List.of();
        return module.getClues().stream()
                .filter(clue -> clueIds.contains(clue.getId()))
                .map(Clue::getTitle)
                .filter(title -> title != null && !title.isBlank())
                .distinct()
                .toList();
    }

    private List<String> resolveNpcNames(AdventureModule module, Set<String> npcIds) {
        if (npcIds == null || npcIds.isEmpty()) return List.of();
        return module.getNpcs().stream()
                .filter(npc -> npcIds.contains(npc.getId()))
                .map(NpcProfile::getName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    private List<String> sortedStrings(Set<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().sorted().toList();
    }

    private String joinLimited(List<String> values, int limit) {
        List<String> trimmed = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .limit(limit)
                .toList();
        if (trimmed.isEmpty()) return "";
        String joined = String.join(" · ", trimmed);
        if (values.size() > limit) {
            joined += "…";
        }
        return joined;
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        return text.substring(0, Math.max(0, maxChars - 1)).trim() + "…";
    }
}
