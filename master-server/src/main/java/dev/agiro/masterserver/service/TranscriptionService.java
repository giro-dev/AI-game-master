package dev.agiro.masterserver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.opensearch.client.Request;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Wraps the {@code faster-whisper-server} HTTP API for speech-to-text and
 * persists transcripts in the OpenSearch {@code transcriptions} index.
 *
 * <p>The faster-whisper-server exposes an OpenAI-compatible endpoint at
 * {@code POST /v1/audio/transcriptions} taking a multipart {@code file}.
 */
@Slf4j
@Service
public class TranscriptionService {

    private final RestClient openSearchClient;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${whisper.url:http://localhost:9300}")
    private String whisperUrl;

    @Value("${whisper.model:large-v3}")
    private String whisperModel;

    @Value("${whisper.language:ca}")
    private String whisperLanguage;

    public TranscriptionService(ObjectMapper objectMapper,
                                @Value("${opensearch.host:localhost}") String opensearchHost,
                                @Value("${opensearch.port:9200}") int opensearchPort) {
        this.objectMapper = objectMapper;
        RestClientBuilder builder = RestClient.builder(
                new HttpHost(opensearchHost, opensearchPort, "http")
        );
        this.openSearchClient = builder.build();
        this.restTemplate = new RestTemplate();
    }

    /**
     * Transcribe a raw audio buffer using Whisper. Audio format must be a
     * container Whisper accepts (WAV/OGG/WEBM/MP3/…).
     *
     * @return the transcribed text, or an empty string on failure.
     */
    public String transcribeAudio(byte[] audioData) {
        if (audioData == null || audioData.length == 0) {
            log.warn("transcribeAudio called with empty payload");
            return "";
        }

        String url = whisperUrl.replaceAll("/+$", "") + "/v1/audio/transcriptions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ByteArrayResource fileResource = new ByteArrayResource(audioData) {
            @Override
            public String getFilename() {
                return "audio.wav";
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);
        body.add("model", whisperModel);
        if (whisperLanguage != null && !whisperLanguage.isBlank()) {
            body.add("language", whisperLanguage);
        }
        body.add("response_format", "json");

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        try {
            String responseBody = restTemplate.postForObject(url, requestEntity, String.class);
            if (responseBody == null || responseBody.isBlank()) {
                return "";
            }
            JsonNode tree = objectMapper.readTree(responseBody);
            JsonNode textNode = tree.get("text");
            return textNode != null && !textNode.isNull() ? textNode.asText("").trim() : "";
        } catch (RestClientException e) {
            log.error("Whisper transcription HTTP call failed: {}", e.getMessage());
            return "";
        } catch (IOException e) {
            log.error("Failed to parse Whisper response", e);
            return "";
        }
    }

    public void saveTranscription(String transcriptionId, String transcription, Map<String, Object> metadata) throws IOException {
        Request request = new Request("PUT", "/transcriptions/_doc/" + transcriptionId);

        Map<String, Object> doc = new HashMap<>();
        doc.put("transcription", transcription);
        if (metadata != null) {
            doc.putAll(metadata);
        }

        request.setJsonEntity(objectMapper.writeValueAsString(doc));
        openSearchClient.performRequest(request);
    }

    public String getTranscription(String transcriptionId) throws IOException {
        Request request = new Request("GET", "/transcriptions/_doc/" + transcriptionId);
        return openSearchClient.performRequest(request).getEntity().toString();
    }
}
