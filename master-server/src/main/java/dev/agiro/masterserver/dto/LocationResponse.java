package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A fully generated location with rooms, hazards, and inhabitants.
 * Stored in OpenSearch as a persistent world-state document.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationResponse {

    private boolean success;

    /** Unique identifier — stored as the OpenSearch document ID */
    private String locationId;

    private String worldId;
    private String name;
    private String type;

    /** Overall narrative description of the location */
    private String description;

    /** Atmospheric details (lighting, smell, sound) */
    private String atmosphere;

    /** Rooms / areas within the location */
    private List<RoomDto> rooms;

    /** Factions that have a presence here */
    private List<String> presentFactions;

    /** GM-facing design reasoning */
    private String reasoning;

    // ── Nested types ────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoomDto {
        private String roomId;
        private String name;
        private String description;
        /** Names of creatures or NPCs in this room */
        private List<String> encounters;
        /** Items of interest or treasure */
        private List<String> items;
        /** Traps and hazards */
        private List<String> traps;
        /** Room names this area connects to */
        private List<String> connections;
        /** Brief GM note on purpose or secrets */
        private String gmNote;
    }
}
