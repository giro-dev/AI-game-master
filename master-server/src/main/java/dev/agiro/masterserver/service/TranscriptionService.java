package dev.agiro.masterserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.SpeakerContextDto;
import dev.agiro.masterserver.dto.TranscriptionResult;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Transcription service — converts audio bytes to text using OpenAI Whisper
 * and persists the enriched result to OpenSearch.
 * <p>
 * Speaker metadata (which Foundry user/character was speaking) is provided
 * by the client and stored alongside the transcript text so the AI GM can
 * use recent session dialogue as context.
 */
@Slf4j
@Service
public class TranscriptionService {

    static final String TRANSCRIPTIONS_INDEX = "session-transcriptions";

    private final RestClient whisperRestClient;
    private final OpenSearchClient openSearchClient;
    private final ObjectMapper objectMapper;

    @Value("${game-master.transcription.model:whisper-1}")
    private String transcriptionModel;

    @Value("${game-master.transcription.language:}")
    private String defaultLanguage;

    public TranscriptionService(
            @Value("${spring.ai.openai.api-key}") String openAiApiKey,
            OpenSearchClient openSearchClient,
            ObjectMapper objectMapper) {
        this.openSearchClient = openSearchClient;
        this.objectMapper = objectMapper;
        this.whisperRestClient = RestClient.builder()
                .baseUrl("https://api.openai.com")
                .defaultHeader("Authorization", "Bearer " + openAiApiKey)
                .build();
        ensureIndex();
    }

    // ── Transcription ─────────────────────────────────────────────────────

    /**
     * Transcribe raw audio bytes using OpenAI Whisper.
     * The result is enriched with {@code speaker} context and stored to OpenSearch.
     *
     * @param audioData    Raw audio bytes (WebM, MP4, WAV, OGG, MP3, or FLAC)
     * @param audioFormat  File extension / MIME hint (e.g. "webm", "wav", "mp3"). Used to name the file.
     * @param speaker      Speaker metadata captured by the Foundry module.
     * @return completed {@link TranscriptionResult} — even on Whisper error the result has {@code error} set
     */
    public TranscriptionResult transcribe(byte[] audioData, String audioFormat, SpeakerContextDto speaker) {
        String transcriptionId = UUID.randomUUID().toString();
        long now = Instant.now().toEpochMilli();

        if (audioData == null || audioData.length == 0) {
            return buildError(transcriptionId, speaker, "Empty audio data", now);
        }

        try {
            String whisperText = callWhisper(audioData, audioFormat);
            TranscriptionResult result = TranscriptionResult.builder()
                    .transcriptionId(transcriptionId)
                    .text(whisperText)
                    .model(transcriptionModel)
                    .speaker(speaker)
                    .recordedAt(now)
                    .transcribedAt(Instant.now().toEpochMilli())
                    .confidence(1.0)
                    .stored(false)
                    .build();

            storeTranscription(result);
            result.setStored(true);
            return result;

        } catch (Exception e) {
            log.error("[Transcription] Whisper call failed: {}", e.getMessage());
            return buildError(transcriptionId, speaker, e.getMessage(), now);
        }
    }

    /**
     * Legacy byte-array only transcription — no speaker context.
     * Kept for backward compatibility with the existing WebSocket handler.
     */
    public String transcribeAudio(byte[] audioData) {
        TranscriptionResult result = transcribe(audioData, "webm", null);
        return result.getError() != null ? result.getError() : result.getText();
    }

    // ── OpenSearch persistence ─────────────────────────────────────────────

    public void storeTranscription(TranscriptionResult result) {
        try {
            Map<String, Object> doc = objectMapper.convertValue(result, Map.class);
            IndexResponse r = openSearchClient.index(i -> i
                    .index(TRANSCRIPTIONS_INDEX)
                    .id(result.getTranscriptionId())
                    .document(doc));
            log.debug("[Transcription] Stored {} → result={}", result.getTranscriptionId(), r.result().jsonValue());
        } catch (Exception e) {
            log.warn("[Transcription] Failed to store transcription {}: {}", result.getTranscriptionId(), e.getMessage());
        }
    }

    /**
     * Retrieve the most recent transcription results for a given world, newest-first.
     */
    public List<TranscriptionResult> findRecentByWorld(String worldId, int limit) {
        try {
            SearchResponse<Map> response = openSearchClient.search(s -> s
                    .index(TRANSCRIPTIONS_INDEX)
                    .size(limit)
                    .query(q -> q.term(t -> t.field("speaker.worldId").value(v -> v.stringValue(worldId))))
                    .sort(o -> o.field(f -> f.field("transcribedAt")
                            .order(org.opensearch.client.opensearch._types.SortOrder.Desc))),
                    Map.class);
            return response.hits().hits().stream()
                    .map(Hit::source)
                    .filter(Objects::nonNull)
                    .map(s -> objectMapper.convertValue(s, TranscriptionResult.class))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.debug("[Transcription] findRecentByWorld({}) failed: {}", worldId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Retrieve recent transcriptions for a specific speaker (Foundry userId) in a world.
     */
    public List<TranscriptionResult> findRecentBySpeaker(String worldId, String userId, int limit) {
        try {
            SearchResponse<Map> response = openSearchClient.search(s -> s
                    .index(TRANSCRIPTIONS_INDEX)
                    .size(limit)
                    .query(q -> q.bool(b -> b
                            .must(m -> m.term(t -> t.field("speaker.worldId").value(v -> v.stringValue(worldId))))
                            .must(m -> m.term(t -> t.field("speaker.userId").value(v -> v.stringValue(userId))))
                    ))
                    .sort(o -> o.field(f -> f.field("transcribedAt")
                            .order(org.opensearch.client.opensearch._types.SortOrder.Desc))),
                    Map.class);
            return response.hits().hits().stream()
                    .map(Hit::source)
                    .filter(Objects::nonNull)
                    .map(s -> objectMapper.convertValue(s, TranscriptionResult.class))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.debug("[Transcription] findRecentBySpeaker failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Whisper REST call ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String callWhisper(byte[] audioData, String audioFormat) {
        String filename = "audio." + sanitizeFormat(audioFormat);
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("model", transcriptionModel);
        body.part("response_format", "json");
        if (defaultLanguage != null && !defaultLanguage.isBlank()) {
            body.part("language", defaultLanguage);
        }
        body.part("file", new ByteArrayResource(audioData) {
            @Override
            public String getFilename() { return filename; }
        }, MediaType.APPLICATION_OCTET_STREAM);

        String json = whisperRestClient.post()
                .uri("/v1/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build())
                .retrieve()
                .body(String.class);

        if (json == null) return "";
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            return (String) parsed.getOrDefault("text", "");
        } catch (Exception e) {
            log.warn("[Transcription] Failed to parse Whisper JSON response: {}", e.getMessage());
            return json;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private TranscriptionResult buildError(String id, SpeakerContextDto speaker, String error, long now) {
        return TranscriptionResult.builder()
                .transcriptionId(id)
                .text("")
                .speaker(speaker)
                .recordedAt(now)
                .transcribedAt(Instant.now().toEpochMilli())
                .model(transcriptionModel)
                .stored(false)
                .error(error)
                .build();
    }

    private String sanitizeFormat(String format) {
        if (format == null || format.isBlank()) return "webm";
        return format.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }

    private void ensureIndex() {
        try {
            boolean exists = openSearchClient.indices().exists(e -> e.index(TRANSCRIPTIONS_INDEX)).value();
            if (!exists) {
                openSearchClient.indices().create(c -> c
                        .index(TRANSCRIPTIONS_INDEX)
                        .settings(s -> s.numberOfShards("1").numberOfReplicas("0")));
                log.info("[Transcription] Created OpenSearch index '{}'", TRANSCRIPTIONS_INDEX);
            }
        } catch (Exception e) {
            log.warn("[Transcription] Could not ensure index '{}': {}", TRANSCRIPTIONS_INDEX, e.getMessage());
        }
    }
}
