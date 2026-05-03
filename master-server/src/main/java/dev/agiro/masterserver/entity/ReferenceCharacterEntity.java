package dev.agiro.masterserver.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persisted form of {@link dev.agiro.masterserver.dto.ReferenceCharacterDto}.
 * Composite logical key (systemId + actorType) is collapsed into {@link #id}.
 */
@Entity
@Table(name = "reference_characters")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceCharacterEntity {

    /** {@code systemId:actorType}. */
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "system_id", nullable = false)
    private String systemId;

    @Column(name = "actor_type", nullable = false)
    private String actorType;

    @Column(name = "label")
    private String label;

    @Column(name = "captured_at")
    private Long capturedAt;

    @Column(name = "actor_data_json", columnDefinition = "TEXT")
    private String actorDataJson;

    @Column(name = "items_json", columnDefinition = "TEXT")
    private String itemsJson;
}
