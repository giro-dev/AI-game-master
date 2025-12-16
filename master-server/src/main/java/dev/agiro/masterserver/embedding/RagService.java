package dev.agiro.masterserver.embedding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingService embeddingService;

    /**
     * Search for similar chunks based on a query string
     */
    public List<DocumentChunkEntity> searchSimilar(String query, int topK) {
        float[] queryEmbedding = embeddingService.createEmbedding(query);
        String embeddingString = floatArrayToString(queryEmbedding);
        return documentChunkRepository.findSimilarChunks(embeddingString, topK);
    }

    /**
     * Search for similar chunks filtered by Foundry system (dnd5e, pf2e, etc.)
     */
    public List<DocumentChunkEntity> searchSimilarBySystem(String query, String foundrySystem, int topK) {
        float[] queryEmbedding = embeddingService.createEmbedding(query);
        String embeddingString = floatArrayToString(queryEmbedding);
        return documentChunkRepository.findSimilarChunksBySystem(embeddingString, foundrySystem, topK);
    }

    /**
     * Search for similar chunks filtered by source document
     */
    public List<DocumentChunkEntity> searchSimilarByDocument(String query, String sourceDocument, int topK) {
        float[] queryEmbedding = embeddingService.createEmbedding(query);
        String embeddingString = floatArrayToString(queryEmbedding);
        return documentChunkRepository.findSimilarChunksByDocument(embeddingString, sourceDocument, topK);
    }

    /**
     * Search with a distance threshold (only return results within threshold)
     */
    public List<DocumentChunkEntity> searchSimilarWithThreshold(String query, double maxDistance, int topK) {
        float[] queryEmbedding = embeddingService.createEmbedding(query);
        String embeddingString = floatArrayToString(queryEmbedding);
        return documentChunkRepository.findSimilarChunksWithThreshold(embeddingString, maxDistance, topK);
    }

    /**
     * Build context string for RAG prompts
     */
    public String buildContextForQuery(String query, int topK) {
        List<DocumentChunkEntity> chunks = searchSimilar(query, topK);
        return formatChunksAsContext(chunks);
    }

    /**
     * Build context string filtered by system
     */
    public String buildContextForQueryBySystem(String query, String foundrySystem, int topK) {
        List<DocumentChunkEntity> chunks = searchSimilarBySystem(query, foundrySystem, topK);
        return formatChunksAsContext(chunks);
    }

    private String formatChunksAsContext(List<DocumentChunkEntity> chunks) {
        if (chunks.isEmpty()) {
            return "No relevant context found.";
        }

        StringBuilder context = new StringBuilder();
        context.append("=== RELEVANT CONTEXT FROM RULEBOOKS ===\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunkEntity chunk = chunks.get(i);
            context.append(String.format("[%d] Source: %s (Page %d)\n",
                    i + 1,
                    chunk.getSourceDocument() != null ? chunk.getSourceDocument() : "Unknown",
                    chunk.getPage() != null ? chunk.getPage() : 0));
            context.append(String.format("Title: %s\n", chunk.getTitle() != null ? chunk.getTitle() : "N/A"));
            context.append(String.format("Type: %s\n", chunk.getChunkType() != null ? chunk.getChunkType() : "text"));
            context.append("Content:\n");
            context.append(chunk.getContent());
            context.append("\n\n---\n\n");
        }

        return context.toString();
    }

    /**
     * Convert float array to pgvector string format: [0.1,0.2,0.3,...]
     */
    private String floatArrayToString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}

