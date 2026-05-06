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
 * An NPC the players may interact with.
 * Stores both narrative attributes (personality, secrets, objectives) and
 * voice-synthesis parameters used by {@link dev.agiro.masterserver.service.TtsService}.
 */
@Entity
@Table(name = "adventure_npc")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NpcProfile {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String personality;

    @Column(columnDefinition = "TEXT")
    private String secrets;

    @Column(columnDefinition = "TEXT")
    private String objectives;

    /** Normalized to {@code male}, {@code female} or {@code unknown}. */
    @Column(name = "gender")
    private String gender;

    /** Logical voice id resolved against {@code piper-tts.voices} configuration. */
    @Column(name = "voice_id")
    private String voiceId;

    @Column(name = "voice_pitch")
    private Float voicePitch;

    @Column(name = "voice_speed")
    private Float voiceSpeed;

    /** Current attitude towards the players (e.g. "friendly", "neutral", "hostile"). */
    @Column(name = "current_disposition")
    private String currentDisposition;
}
