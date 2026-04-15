package dev.agiro.masterserver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.SpeakerContextDto;
import dev.agiro.masterserver.dto.TranscriptionResult;
import dev.agiro.masterserver.service.TranscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * REST API for session transcription.
 * <p>
 * <ul>
 *   <li>{@code POST /gm/transcription/submit} — submit audio + speaker metadata for transcription</li>
 *   <li>{@code GET  /gm/transcription/{worldId}/recent} — recent transcripts for a world</li>
 *   <li>{@code GET  /gm/transcription/{worldId}/speaker/{userId}} — transcripts for one speaker</li>
 * </ul>
 * <p>
 * The {@code submit} endpoint accepts {@code multipart/form-data} with:
 * <ul>
 *   <li>{@code audio} — the audio file (WebM, WAV, MP3, OGG, or FLAC)</li>
 *   <li>{@code speaker} — JSON-serialised {@link SpeakerContextDto} (optional)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/gm/transcription")
@CrossOrigin(origins = "*")
public class TranscriptionController {

    private final TranscriptionService transcriptionService;
    private final ObjectMapper objectMapper;

    public TranscriptionController(TranscriptionService transcriptionService,
                                   ObjectMapper objectMapper) {
        this.transcriptionService = transcriptionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Submit an audio clip for transcription.
     * <p>
     * The Foundry module sends this immediately after the active speaker stops talking,
     * attaching the speaker's Foundry user + character information as metadata.
     *
     * @param audio   the audio file part
     * @param speakerJson JSON string of {@link SpeakerContextDto} (optional — if absent the transcript
     *                    is stored without speaker attribution)
     */
    @PostMapping(value = "/submit",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TranscriptionResult> submit(
            @RequestPart("audio") MultipartFile audio,
            @RequestPart(value = "speaker", required = false) String speakerJson) {

        log.info("[Transcription] submit: file={} size={} speaker={}",
                audio.getOriginalFilename(), audio.getSize(), speakerJson != null ? "yes" : "no");

        if (audio.isEmpty()) {
            TranscriptionResult err = TranscriptionResult.builder()
                    .error("Audio file is empty").stored(false).build();
            return ResponseEntity.badRequest().body(err);
        }

        try {
            SpeakerContextDto speaker = null;
            if (speakerJson != null && !speakerJson.isBlank()) {
                speaker = objectMapper.readValue(speakerJson, SpeakerContextDto.class);
            }

            String format = extractFormat(audio.getOriginalFilename(), audio.getContentType());
            TranscriptionResult result = transcriptionService.transcribe(audio.getBytes(), format, speaker);

            return result.getError() != null
                    ? ResponseEntity.internalServerError().body(result)
                    : ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("[Transcription] submit failed", e);
            TranscriptionResult err = TranscriptionResult.builder()
                    .error("Internal error: " + e.getMessage()).stored(false).build();
            return ResponseEntity.internalServerError().body(err);
        }
    }

    /**
     * Retrieve the N most recent transcriptions for a given world, newest-first.
     */
    @GetMapping(value = "/{worldId}/recent",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<TranscriptionResult>> recent(
            @PathVariable String worldId,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(transcriptionService.findRecentByWorld(worldId, limit));
    }

    /**
     * Retrieve the N most recent transcriptions for a specific speaker in a world.
     */
    @GetMapping(value = "/{worldId}/speaker/{userId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<TranscriptionResult>> bySpeaker(
            @PathVariable String worldId,
            @PathVariable String userId,
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(transcriptionService.findRecentBySpeaker(worldId, userId, limit));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String extractFormat(String filename, String contentType) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0) return filename.substring(dot + 1).toLowerCase();
        }
        if (contentType != null) {
            return switch (contentType.toLowerCase()) {
                case "audio/webm" -> "webm";
                case "audio/wav", "audio/wave", "audio/x-wav" -> "wav";
                case "audio/mpeg", "audio/mp3" -> "mp3";
                case "audio/ogg" -> "ogg";
                case "audio/flac" -> "flac";
                case "audio/mp4", "video/mp4" -> "mp4";
                default -> "webm";
            };
        }
        return "webm";
    }
}
