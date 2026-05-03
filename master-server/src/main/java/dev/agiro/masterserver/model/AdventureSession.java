package dev.agiro.masterserver.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live state of an {@link AdventureModule} being played.
 * Tracks the current act/scene, what has been discovered, NPC dispositions,
 * the running tension level and a chronological log of significant player decisions.
 */
@Entity
@Table(name = "adventure_session")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdventureSession {

    @Id
    private String id;

    @Column(name = "adventure_module_id", nullable = false)
    private String adventureModuleId;

    @Column(name = "world_id")
    private String worldId;

    @Column(name = "current_act_id")
    private String currentActId;

    @Column(name = "current_scene_id")
    private String currentSceneId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "adventure_session_discovered_clues", joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "clue_id")
    @Builder.Default
    private Set<String> discoveredClueIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "adventure_session_met_npcs", joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "npc_id")
    @Builder.Default
    private Set<String> metNpcIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "adventure_session_npc_dispositions", joinColumns = @JoinColumn(name = "session_id"))
    @MapKeyColumn(name = "npc_id")
    @Column(name = "disposition")
    @Builder.Default
    private Map<String, String> npcDispositions = new HashMap<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "adventure_session_decisions", joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "decision", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> playerDecisionLog = new ArrayList<>();

    @Column(name = "tension_level", nullable = false)
    @Builder.Default
    private int tensionLevel = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
