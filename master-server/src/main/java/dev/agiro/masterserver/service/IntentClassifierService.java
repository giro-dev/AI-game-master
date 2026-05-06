package dev.agiro.masterserver.service;

import dev.agiro.masterserver.dto.IntentClassification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Classifies a transcribed player utterance into one of:
 * {@code action | dialogue | question | out_of_character | unclear}.
 *
 * <p>Used by {@link AdventureDirectorService} to decide whether to ask the
 * player for confirmation before mutating the adventure state.
 */
@Slf4j
@Service
public class IntentClassifierService {

    private static final String SYSTEM_PROMPT = """
            You classify a player's spoken utterance during a tabletop RPG session.
            Output ONLY a single JSON object with these exact fields:

            {
              "intent": "action" | "dialogue" | "question" | "out_of_character" | "unclear",
              "summary": "<one short sentence describing what the player wants>",
              "confidence": <0.0 to 1.0>,
              "requiresConfirmation": <true | false>
            }

            Definitions:
            - "action": the player wants their character to do something (search, attack, sneak, …).
            - "dialogue": the player is speaking IN-CHARACTER to NPCs or other PCs.
            - "question": the player is asking the GM or the rules a question.
            - "out_of_character": the player is speaking as themselves (jokes, breaks, meta, snacks).
            - "unclear": the utterance is ambiguous, broken or off-topic.

            Set requiresConfirmation = true when:
              * confidence < 0.7
              * intent is "unclear"
              * intent is "out_of_character"
            Otherwise set it to false.

            Respond ONLY with the JSON object — no markdown, no commentary.
            """;

    private static final String USER_TEMPLATE = """
            CURRENT SCENE CONTEXT:
            {sceneContext}

            PLAYER UTTERANCE:
            "{transcription}"

            Classify the utterance.
            """;

    private final ChatClient chatClient;
    private final double confirmationThreshold;
    private final ModelRoutingService modelRoutingService;

    public IntentClassifierService(ChatClient.Builder chatClientBuilder,
                                   ModelRoutingService modelRoutingService,
                                   @Value("${adventure.intent-confirmation-threshold:0.7}") double confirmationThreshold) {
        this.modelRoutingService = modelRoutingService;
        this.chatClient = chatClientBuilder
                .defaultOptions(modelRoutingService.optionsFor("intent-classifier"))
                .build();
        this.confirmationThreshold = confirmationThreshold;
    }

    public IntentClassification classify(String transcription, String sceneContext) {
        String safeContext = sceneContext == null ? "" : sceneContext;
        if (transcription == null || transcription.isBlank()) {
            return IntentClassification.builder()
                    .intent("unclear")
                    .summary("Empty transcription")
                    .confidence(0.0)
                    .requiresConfirmation(true)
                    .build();
        }

        try {
            IntentClassification result = modelRoutingService.timed("intent-classifier", null, () ->
                    chatClient.prompt()
                            .system(SYSTEM_PROMPT)
                            .user(u -> u.text(USER_TEMPLATE)
                                    .param("sceneContext", safeContext)
                                    .param("transcription", transcription))
                            .call()
                            .entity(IntentClassification.class));

            if (result == null) {
                return fallback(transcription);
            }
            // Server-side enforcement: never trust the model's flag in isolation.
            boolean needsConfirm = result.isRequiresConfirmation()
                    || result.getConfidence() < confirmationThreshold
                    || "unclear".equalsIgnoreCase(result.getIntent())
                    || "out_of_character".equalsIgnoreCase(result.getIntent());
            result.setRequiresConfirmation(needsConfirm);
            return result;
        } catch (Exception e) {
            log.error("Intent classification failed: {}", e.getMessage());
            return fallback(transcription);
        }
    }

    private IntentClassification fallback(String transcription) {
        return IntentClassification.builder()
                .intent("unclear")
                .summary(transcription)
                .confidence(0.0)
                .requiresConfirmation(true)
                .build();
    }
}
