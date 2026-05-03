package dev.agiro.masterserver.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A discoverable clue inside an {@link AdventureModule}.
 * Core clues are mandatory for finishing the adventure;
 * the AI Director must ensure the players obtain them somehow.
 */
@Entity
@Table(name = "adventure_clue")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Clue {

    @Id
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "discovery_condition", columnDefinition = "TEXT")
    private String discoveryCondition;

    @Column(name = "is_core", nullable = false)
    private boolean isCore;
}
