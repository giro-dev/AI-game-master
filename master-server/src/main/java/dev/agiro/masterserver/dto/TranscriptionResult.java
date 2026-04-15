package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A completed transcription with speaker attribution and metadata.
 * Stored in OpenSearch under the {@code session-transcriptions} index
 * and optionally pushed back to the Foundry module via WebSocket.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranscriptionResult {

    /** Unique identifier for this transcript (UUID) */
    private String transcriptionId;

    /** The raw transcript text returned by Whisper */
    private String text;

    /** Detected language code (e.g. "en", "ca", "es") */
    private String language;

    /** Duration of the audio in seconds (if available) */
    private Double durationSeconds;

    /** Confidence score: 0.0–1.0 (Whisper does not return this directly; set to 1.0) */
    private Double confidence;

    /** Speaker context at the time of recording */
    private SpeakerContextDto speaker;

    /** Epoch-millis timestamp when the audio was recorded (from client) */
    private Long recordedAt;

    /** Epoch-millis timestamp when the transcription was completed (server-side) */
    private Long transcribedAt;

    /** Which transcription model was used (e.g. "whisper-1") */
    private String model;

    /** Whether this transcription was stored to OpenSearch */
    private boolean stored;

    /** Error message if transcription failed */
    private String error;
}
