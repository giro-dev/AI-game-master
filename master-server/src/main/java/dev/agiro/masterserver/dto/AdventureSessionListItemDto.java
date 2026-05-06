package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdventureSessionListItemDto {
    private String id;
    private String sessionName;
    @Builder.Default
    private List<String> participantNames = new ArrayList<>();
    private String sessionSummary;
    private String currentSceneId;
    private String currentSceneTitle;
    private int tensionLevel;
    private Instant createdAt;
    private Instant updatedAt;
}
