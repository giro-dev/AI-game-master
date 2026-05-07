package dev.agiro.masterserver.service;

import dev.agiro.masterserver.model.Act;
import dev.agiro.masterserver.model.AdventureModule;
import dev.agiro.masterserver.model.AdventureSession;
import dev.agiro.masterserver.model.Scene;
import dev.agiro.masterserver.repository.AdventureSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdventureSessionServiceTest {

    @Mock
    AdventureSessionRepository repository;

    AdventureSessionService service;

    @BeforeEach
    void setUp() {
        service = new AdventureSessionService(repository);
    }

    // ─── createSession ────────────────────────────────────────────────────

    @Test
    void createSessionSetsFirstActAndSceneAsCurrent() {
        Scene scene = Scene.builder().id("scene-1").title("Opening").orderIndex(0).build();
        Act act = Act.builder().id("act-1").title("Act 1").orderIndex(0).scenes(List.of(scene)).build();
        AdventureModule module = AdventureModule.builder()
                .id("mod-1").title("Test Adventure").system("dnd5e")
                .acts(List.of(act)).build();

        when(repository.save(any(AdventureSession.class))).thenAnswer(inv -> inv.getArgument(0));

        AdventureSession session = service.createSession(module, "world-42");

        assertEquals("act-1", session.getCurrentActId());
        assertEquals("scene-1", session.getCurrentSceneId());
        assertEquals("world-42", session.getWorldId());
        assertEquals("mod-1", session.getAdventureModuleId());
        assertNotNull(session.getId());
    }

    @Test
    void createSessionWithEmptyModuleHasNullSceneId() {
        AdventureModule module = AdventureModule.builder()
                .id("mod-2").title("Empty Module").system("dnd5e").build();

        when(repository.save(any(AdventureSession.class))).thenAnswer(inv -> inv.getArgument(0));

        AdventureSession session = service.createSession(module, null);

        assertEquals(null, session.getCurrentActId());
        assertEquals(null, session.getCurrentSceneId());
    }

    // ─── registerParticipant ──────────────────────────────────────────────

    @Test
    void registerParticipantAddsNewPlayerAndReturnsTrue() {
        AdventureSession session = emptySession();

        boolean added = service.registerParticipant(session, "Gimli");

        assertTrue(added);
        assertTrue(session.getParticipantNames().contains("Gimli"));
    }

    @Test
    void registerParticipantIgnoresDuplicateAndReturnsFalse() {
        AdventureSession session = emptySession();
        session.getParticipantNames().add("Legolas");

        boolean added = service.registerParticipant(session, "Legolas");

        assertFalse(added);
        assertEquals(1, session.getParticipantNames().size());
    }

    @Test
    void registerParticipantTrimsWhitespace() {
        AdventureSession session = emptySession();

        service.registerParticipant(session, "  Aragorn  ");

        assertTrue(session.getParticipantNames().contains("Aragorn"));
    }

    @Test
    void registerNullParticipantReturnsFalse() {
        AdventureSession session = emptySession();

        boolean added = service.registerParticipant(session, null);

        assertFalse(added);
        assertTrue(session.getParticipantNames().isEmpty());
    }

    @Test
    void registerBlankParticipantReturnsFalse() {
        AdventureSession session = emptySession();

        boolean added = service.registerParticipant(session, "   ");

        assertFalse(added);
        assertTrue(session.getParticipantNames().isEmpty());
    }

    // ─── findScene ────────────────────────────────────────────────────────

    @Test
    void findSceneReturnsPresentForKnownId() {
        Scene scene = Scene.builder().id("scene-99").title("Boss Room").orderIndex(0).build();
        Act act = Act.builder().id("act-1").title("Act 1").orderIndex(0).scenes(List.of(scene)).build();
        AdventureModule module = AdventureModule.builder()
                .id("mod-1").title("Adventure").system("dnd5e").acts(List.of(act)).build();

        Optional<Scene> result = service.findScene(module, "scene-99");

        assertTrue(result.isPresent());
        assertEquals("Boss Room", result.get().getTitle());
    }

    @Test
    void findSceneReturnsEmptyForUnknownId() {
        AdventureModule module = AdventureModule.builder()
                .id("mod-1").title("Adventure").system("dnd5e").build();

        Optional<Scene> result = service.findScene(module, "no-such-scene");

        assertFalse(result.isPresent());
    }

    @Test
    void findSceneReturnsEmptyForNullId() {
        AdventureModule module = AdventureModule.builder()
                .id("mod-1").title("Adventure").system("dnd5e").build();

        Optional<Scene> result = service.findScene(module, null);

        assertFalse(result.isPresent());
    }

    // ─── refreshDerivedFields ─────────────────────────────────────────────

    @Test
    void refreshDerivedFieldsSetsSessionNameWhenBlank() {
        AdventureModule module = AdventureModule.builder()
                .id("mod-1").title("The Dragon Heist").system("dnd5e").build();
        AdventureSession session = AdventureSession.builder()
                .id("sess-1")
                .adventureModuleId("mod-1")
                .createdAt(Instant.parse("2026-05-01T10:00:00Z"))
                .build();

        service.refreshDerivedFields(session, module, null);

        assertNotNull(session.getSessionName());
        assertTrue(session.getSessionName().startsWith("The Dragon Heist"),
                "Session name should start with the module title");
    }

    @Test
    void refreshDerivedFieldsKeepsExistingSessionName() {
        AdventureModule module = AdventureModule.builder()
                .id("mod-1").title("The Dragon Heist").system("dnd5e").build();
        AdventureSession session = AdventureSession.builder()
                .id("sess-1")
                .adventureModuleId("mod-1")
                .sessionName("Custom Session Name")
                .createdAt(Instant.now())
                .build();

        service.refreshDerivedFields(session, module, null);

        assertEquals("Custom Session Name", session.getSessionName());
    }

    @Test
    void refreshDerivedFieldsSetsSummary() {
        Scene scene = Scene.builder().id("scene-1").title("Tavern").orderIndex(0).build();
        Act act = Act.builder().id("act-1").title("Act 1").orderIndex(0).scenes(List.of(scene)).build();
        AdventureModule module = AdventureModule.builder()
                .id("mod-1").title("Adventure").system("dnd5e").acts(List.of(act)).build();
        AdventureSession session = AdventureSession.builder()
                .id("sess-1")
                .adventureModuleId("mod-1")
                .currentSceneId("scene-1")
                .createdAt(Instant.now())
                .build();

        service.refreshDerivedFields(session, module, "The heroes arrived at the tavern.");

        assertNotNull(session.getSessionSummary());
        assertTrue(session.getSessionSummary().contains("Tavern"),
                "Summary should reference current scene title");
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static AdventureSession emptySession() {
        return AdventureSession.builder()
                .id("sess-test")
                .adventureModuleId("mod-1")
                .createdAt(Instant.now())
                .build();
    }
}
