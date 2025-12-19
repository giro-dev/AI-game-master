package dev.agiro.masterserver.embedding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;

import static dev.agiro.masterserver.dto.MetadataField.FILE_NAME;
import static dev.agiro.masterserver.dto.MetadataField.GAME_SYSTEM;

@Service
@Slf4j
public class RagService {

    private final VectorStore vectorStore;
    private final FilterExpressionBuilder b = new FilterExpressionBuilder();

    public RagService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Search for similar chunks based on a query string
     */
    public List<Document> searchSimilar(String query, int topK) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();
        return vectorStore.similaritySearch(searchRequest);
    }

    /**
     * Search for similar chunks filtered by Foundry system (dnd5e, pf2e, etc.)
     */
    public List<Document> searchSimilarBySystem(String query, String foundrySystem, int topK) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(b.eq(GAME_SYSTEM.key(), foundrySystem).build())
                .build();
        return vectorStore.similaritySearch(searchRequest);
    }

    /**
     * Search for similar chunks filtered by source document
     */
    public List<Document> searchSimilarByDocument(String query, String sourceDocument, int topK) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(b.eq(FILE_NAME.key(), sourceDocument).build())
                .build();
        return vectorStore.similaritySearch(searchRequest);
    }

    /**
     * Search with a distance threshold (only return results within threshold)
     */
    public List<Document> searchSimilarWithThreshold(String query, double maxDistance, int topK) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(maxDistance)
                .build();
        return vectorStore.similaritySearch(searchRequest);
    }

}

