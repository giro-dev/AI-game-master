package dev.agiro.masterserver.service;

import dev.agiro.masterserver.model.AdventureModule;
import dev.agiro.masterserver.model.AdventureSession;
import dev.agiro.masterserver.model.Clue;
import dev.agiro.masterserver.model.NpcProfile;
import dev.agiro.masterserver.model.Scene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Exposes read-only adventure session state as {@code @Tool}s so that the
 * director LLM can query live session state on demand rather than having all
 * state pre-injected into every prompt.
 *
 * <p>Register this component on director ChatClient calls via
 * {@code .tools(adventureStateTool)}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdventureStateTool {

    private final AdventureSessionService adventureSessionService;

    /** Returns a text description of the current scene. */
    @Tool(description = "Get the current scene information (title, read-aloud text, GM notes, NPC IDs, clue IDs) for the active adventure session.")
    public String getCurrentScene(AdventureSession session, AdventureModule module) {
        if (session == null || module == null) return "(no active session)";
        Scene scene = adventureSessionService.findScene(module, session.getCurrentSceneId()).orElse(null);
        if (scene == null) return "(scene not found)";
        return "Scene: " + scene.getTitle()
                + "\nRead-aloud: " + safe(scene.getReadAloudText())
                + "\nGM notes: " + safe(scene.getGmNotes())
                + "\nNPC IDs: " + scene.getNpcIds()
                + "\nClue IDs: " + scene.getClueIds();
    }

    /** Returns the titles of all clues discovered so far in the session. */
    @Tool(description = "Get the list of clue titles discovered so far in the adventure session.")
    public List<String> getDiscoveredClues(AdventureSession session, AdventureModule module) {
        if (session == null || module == null || session.getDiscoveredClueIds() == null) return List.of();
        return module.getClues().stream()
                .filter(c -> session.getDiscoveredClueIds().contains(c.getId()))
                .map(Clue::getTitle)
                .collect(Collectors.toList());
    }

    /** Returns a map of NPC id → current disposition for NPCs the players have met. */
    @Tool(description = "Get the current disposition of each NPC that the players have encountered so far.")
    public Map<String, String> getNpcDispositions(AdventureSession session, AdventureModule module) {
        if (session == null || module == null || session.getMetNpcIds() == null) return Map.of();
        return module.getNpcs().stream()
                .filter(npc -> session.getMetNpcIds().contains(npc.getId()))
                .collect(Collectors.toMap(NpcProfile::getId, npc -> safe(npc.getCurrentDisposition())));
    }

    /** Returns the current tension level (0–10) of the adventure session. */
    @Tool(description = "Get the current narrative tension level (0 to 10) of the adventure session.")
    public int getTensionLevel(AdventureSession session) {
        return session != null ? session.getTensionLevel() : 0;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
