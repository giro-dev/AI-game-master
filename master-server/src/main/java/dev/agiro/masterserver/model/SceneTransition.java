package dev.agiro.masterserver.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Describes a possible exit from a {@link Scene}.
 * The {@code condition} is interpreted by the AI Director;
 * {@code targetSceneId} is the scene to move to once the condition is met.
 */
@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SceneTransition {

    @Column(name = "target_scene_id")
    private String targetSceneId;

    @Column(name = "condition_text", columnDefinition = "TEXT")
    private String condition;
}
