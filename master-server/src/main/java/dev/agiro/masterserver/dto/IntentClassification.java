package dev.agiro.masterserver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Classification of a player's transcribed utterance.
 *
 * <p>{@code intent} is one of:
 * <ul>
 *   <li>{@code action} — the player wants to do something in-fiction</li>
 *   <li>{@code dialogue} — the player is talking in-character</li>
 *   <li>{@code question} — the player is asking the GM something</li>
 *   <li>{@code out_of_character} — the player stepped out of fiction</li>
 *   <li>{@code unclear} — could not be classified confidently</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IntentClassification {
    private String intent;
    private String summary;
    private double confidence;
    private boolean requiresConfirmation;
}
