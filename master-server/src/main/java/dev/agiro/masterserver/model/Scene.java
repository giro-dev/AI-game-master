package dev.agiro.masterserver.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * A single locatable beat inside an {@link Act}: room, encounter, conversation, …
 * Scenes hold the read-aloud text the GM narrates, GM-only notes,
 * the clues / NPCs available within and the transitions out.
 */
@Entity
@Table(name = "adventure_scene")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Scene {

    @Id
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(name = "read_aloud_text", columnDefinition = "TEXT")
    private String readAloudText;

    @Column(name = "gm_notes", columnDefinition = "TEXT")
    private String gmNotes;

    @Column(name = "order_index")
    private int orderIndex;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "adventure_scene_clues", joinColumns = @JoinColumn(name = "scene_id"))
    @Column(name = "clue_id")
    @Builder.Default
    private List<String> clueIds = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "adventure_scene_npcs", joinColumns = @JoinColumn(name = "scene_id"))
    @Column(name = "npc_id")
    @Builder.Default
    private List<String> npcIds = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "adventure_scene_transitions", joinColumns = @JoinColumn(name = "scene_id"))
    @Builder.Default
    private List<SceneTransition> transitions = new ArrayList<>();
}
