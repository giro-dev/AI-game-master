package dev.agiro.masterserver.embedding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GenAiEmbeddingService implements EmbeddingService {

    private final EmbeddingModel embeddingModel;

    @Override
    public float[] createEmbedding(String text) {
        try {
            EmbeddingResponse response = embeddingModel.embedForResponse(List.of(text));
            float[] embedding = response.getResult().getOutput();
            log.debug("Created embedding with {} dimensions for text: {}...",
                    embedding.length,
                    text.substring(0, Math.min(50, text.length())));
            return embedding;
        } catch (Exception e) {
            log.error("Failed to create embedding: {}", e.getMessage());
            throw new RuntimeException("Failed to create embedding", e);
        }
    }
}
