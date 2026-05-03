package dev.agiro.masterserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.opensearch.client.Request;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class TranscriptionService {

    private final OpenAiAudioTranscriptionModel transcriptionModel;
    private final RestClient opensearchClient;
    private final ObjectMapper objectMapper;
    private final String defaultLanguage;

    public TranscriptionService(
            OpenAiAudioTranscriptionModel transcriptionModel,
            ObjectMapper objectMapper,
            @Value("${transcription.opensearch-url:http://localhost:9200}") String opensearchUrl,
            @Value("${transcription.default-language:ca}") String defaultLanguage) {
        this.transcriptionModel = transcriptionModel;
        this.objectMapper = objectMapper;
        this.defaultLanguage = defaultLanguage;

        RestClientBuilder builder = RestClient.builder(HttpHost.create(opensearchUrl));
        this.opensearchClient = builder.build();
    }

    public String transcribeAudio(byte[] audioData) {
        return transcribeAudio(audioData, defaultLanguage);
    }

    public String transcribeAudio(byte[] audioData, String language) {
        log.info("Transcribing audio ({} bytes) via Spring AI, language={}", audioData.length, language);

        var audioResource = new ByteArrayResource(audioData) {
            @Override
            public String getFilename() {
                return "audio.wav";
            }
        };

        var options = OpenAiAudioTranscriptionOptions.builder()
                .language(language)
                .build();

        AudioTranscriptionResponse response = transcriptionModel.call(
                new AudioTranscriptionPrompt(audioResource, options)
        );

        String text = response.getResult().getOutput();
        log.info("Transcription completed: {} chars", text != null ? text.length() : 0);
        return text;
    }

    public void saveTranscription(String transcriptionId, String transcription, Map<String, Object> metadata) throws IOException {
        Request request = new Request("PUT", "/transcriptions/_doc/" + transcriptionId);

        Map<String, Object> document = new HashMap<>(metadata);
        document.put("text", transcription);
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
