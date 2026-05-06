package dev.agiro.masterserver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RollDecision {
    private boolean needsRoll;
    private String actionType;
    private String skill;
    private String ability;
    private String difficulty;
    private String narration;
    private String reasoning;
    private String ruleReference;
}
