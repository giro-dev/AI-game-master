package dev.agiro.masterserver.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * A pre-authored adventure module loaded into the system.
 * Contains the narrative structure (acts/scenes), NPCs and clues
 * that the {@link dev.agiro.masterserver.service.AdventureDirectorService}
 * uses to drive a play session.
 */
@Entity
@Table(name = "adventure_module")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdventureModule {

    @Id
    private String id;

    @Column(nullable = false)
    private String title;

    /** Foundry game system the module targets (e.g. "CoC7", "dnd5e"). */
    @Column(nullable = false)
    private String system;

    @Column(columnDefinition = "TEXT")
    private String synopsis;

    /** Optional world the module was authored for. */
    @Column(name = "world_id")
    private String worldId;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "adventure_module_id")
    @OrderBy("orderIndex ASC")
    @Builder.Default
    private List<Act> acts = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "adventure_module_id")
    @Builder.Default
    private List<NpcProfile> npcs = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "adventure_module_id")
    @Builder.Default
    private List<Clue> clues = new ArrayList<>();
}
