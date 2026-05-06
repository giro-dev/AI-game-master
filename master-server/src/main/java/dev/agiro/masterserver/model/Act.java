package dev.agiro.masterserver.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
 * A major narrative beat in an {@link AdventureModule}.
 * Each Act groups a sequence of {@link Scene}s that share a common goal.
 */
@Entity
@Table(name = "adventure_act")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Act {

    @Id
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "act_id")
    @OrderBy("orderIndex ASC")
    @Builder.Default
    private List<Scene> scenes = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "adventure_act_entry_conditions", joinColumns = @JoinColumn(name = "act_id"))
    @Column(name = "condition_text", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> entryConditions = new ArrayList<>();
}
