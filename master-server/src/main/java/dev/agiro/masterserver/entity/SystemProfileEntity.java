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
 * Persisted form of {@link dev.agiro.masterserver.dto.SystemProfileDto}.
 * The full DTO is JSON-serialised into {@link #profileJson} for portability
 * across schema evolutions.
 */
@Entity
@Table(name = "system_profiles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemProfileEntity {

    @Id
    @Column(name = "system_id", nullable = false)
    private String systemId;

    @Column(name = "system_version")
    private String systemVersion;

    @Column(name = "system_title")
    private String systemTitle;

    @Column(name = "last_updated")
    private Long lastUpdated;

    @Column(name = "enriched_from_manuals", nullable = false)
    private boolean enrichedFromManuals;

    @Column(name = "profile_json", columnDefinition = "TEXT", nullable = false)
    private String profileJson;
}
