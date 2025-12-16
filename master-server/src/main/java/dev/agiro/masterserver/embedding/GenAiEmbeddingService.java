package dev.agiro.masterserver.embedding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GenAiEmbeddingService implements EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public GenAiEmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public float[] createEmbedding(String text) {
        try {
            EmbeddingResponse response = embeddingModel.embedForResponse(java.util.List.of(text));
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
