package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdventureSessionStateDto {
    private String sessionId;
    private String adventureId;
    private String sessionName;
    @Builder.Default
    private List<String> participantNames = new ArrayList<>();
    private String sessionSummary;
    private String currentActId;
    private String currentSceneId;
    private AdventureSceneDto currentScene;
    @Builder.Default
    private List<String> discoveredClues = new ArrayList<>();
    @Builder.Default
    private List<String> metNpcs = new ArrayList<>();
    private int tensionLevel;
}
