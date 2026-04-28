package dev.agiro.masterserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.TranscriptionResult;
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
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class TranscriptionService {

    private final RestClient opensearchClient;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String whisperUrl;
    private final String defaultLanguage;

    public TranscriptionService(
            ObjectMapper objectMapper,
            @Value("${transcription.whisper-url:http://localhost:9000}") String whisperUrl,
            @Value("${transcription.opensearch-url:http://localhost:9200}") String opensearchUrl,
            @Value("${transcription.default-language:ca}") String defaultLanguage) {
        this.objectMapper = objectMapper;
        this.whisperUrl = whisperUrl;
        this.defaultLanguage = defaultLanguage;
        this.restTemplate = new RestTemplate();

        RestClientBuilder builder = RestClient.builder(HttpHost.create(opensearchUrl));
        this.opensearchClient = builder.build();
    }

    public TranscriptionResult transcribeAudio(byte[] audioData) {
        return transcribeAudio(audioData, defaultLanguage);
    }

    public TranscriptionResult transcribeAudio(byte[] audioData, String language) {
        log.info("Sending audio ({} bytes) to Whisper service for transcription, language={}", audioData.length, language);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("audio", new ByteArrayResource(audioData) {
            @Override
            public String getFilename() {
                return "audio.wav";
            }
        });
        body.add("language", language);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        ResponseEntity<TranscriptionResult> response = restTemplate.postForEntity(
                whisperUrl + "/transcribe",
                requestEntity,
                TranscriptionResult.class
        );

        TranscriptionResult result = response.getBody();
        if (result != null) {
            log.info("Transcription completed: {} chars, duration={}s, language={}",
                    result.getText().length(), result.getDuration(), result.getLanguage());
        }
        return result;
    }

    public void saveTranscription(String transcriptionId, TranscriptionResult transcription, Map<String, Object> metadata) throws IOException {
        Request request = new Request("PUT", "/transcriptions/_doc/" + transcriptionId);

        Map<String, Object> document = new HashMap<>(metadata);
        document.put("text", transcription.getText());
        document.put("language", transcription.getLanguage());
        document.put("duration", transcription.getDuration());
        document.put("segments", transcription.getSegments());
        document.put("timestamp", System.currentTimeMillis());

        request.setJsonEntity(objectMapper.writeValueAsString(document));
        opensearchClient.performRequest(request);
        log.debug("Saved transcription {} to OpenSearch", transcriptionId);
    }

    public String getTranscription(String transcriptionId) throws IOException {
        Request request = new Request("GET", "/transcriptions/_doc/" + transcriptionId);
        return opensearchClient.performRequest(request).getEntity().toString();
    }
}
