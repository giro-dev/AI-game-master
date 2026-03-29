package dev.agiro.masterserver.service;

import org.apache.http.HttpHost;
import org.opensearch.client.Request;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

@Service
public class TranscriptionService {

    private final RestClient client;

    public TranscriptionService() {
        RestClientBuilder builder = RestClient.builder(
                new HttpHost("localhost", 9200, "http")
        );
        this.client = builder.build();
    }

    public String transcribeAudio(byte[] audioData) {
        // Placeholder for transcription logic
        // Integrate a local transcription model like Vosk or Whisper here
        return "Transcription result (Catalan)";
    }

    public void saveTranscription(String transcriptionId, String transcription, Map<String, Object> metadata) throws IOException {
        Request request = new Request("PUT", "/transcriptions/" + transcriptionId);

        // Construct JSON entity with transcription and metadata
        StringBuilder jsonEntity = new StringBuilder();
        jsonEntity.append("{");
        jsonEntity.append("\"transcription\": \"").append(transcription).append("\",");

        // Add metadata fields
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            jsonEntity.append("\"").append(entry.getKey()).append("\": ")
                      .append(entry.getValue() instanceof String ? "\"" + entry.getValue() + "\"" : entry.getValue())
                      .append(",");
        }

        // Remove trailing comma and close JSON
        if (jsonEntity.charAt(jsonEntity.length() - 1) == ',') {
            jsonEntity.deleteCharAt(jsonEntity.length() - 1);
        }
        jsonEntity.append("}");

        request.setJsonEntity(jsonEntity.toString());
        client.performRequest(request);
    }

    public String getTranscription(String transcriptionId) throws IOException {
        Request request = new Request("GET", "/transcriptions/" + transcriptionId);
        return client.performRequest(request).getEntity().toString();
    }
}
