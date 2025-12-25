package dev.agiro.masterserver.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for Retrieval-Augmented Generation (RAG)
 * Searches for relevant context from the knowledge base to enhance AI responses
 * Uses Spring AI VectorStore for similarity search
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGService {

    private final VectorStore vectorStore;

    /**
     * Search for relevant context about item types for character generation
     * 
     * @param itemTypes List of item types to search for
     * @param systemId The game system ID (e.g., "hitos", "dnd5e")
     * @param topK Number of most relevant chunks to retrieve per item type
     * @return Formatted context string with relevant information
     */
    public String searchItemContext(List<String> itemTypes, String systemId, int topK) {
        if (itemTypes == null || itemTypes.isEmpty()) {
            log.debug("No item types provided, skipping RAG search");
            return "";
        }

        log.info("Searching RAG context for {} item types in system: {}", itemTypes.size(), systemId);

        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("=== RELEVANT SYSTEM INFORMATION FROM RULES ===\n\n");

        for (String itemType : itemTypes) {
            try {
                String context = searchItemTypeContext(itemType, systemId, topK);
                if (!context.isEmpty()) {
                    contextBuilder.append("--- ").append(itemType).append(" ---\n");
                    contextBuilder.append(context).append("\n\n");
                }
            } catch (Exception e) {
                log.warn("Failed to search context for item type '{}': {}", itemType, e.getMessage());
            }
        }

        String result = contextBuilder.toString();
        if (result.length() <= 50) { // Only header
            log.debug("No relevant context found in RAG");
            return "";
        }

        log.info("RAG context retrieved: {} characters", result.length());
        return result;
    }

    /**
     * Search for context about a specific item type using VectorStore
     */
    private String searchItemTypeContext(String itemType, String systemId, int topK) {
        // Build search query for this item type
        String searchQuery = String.format(
            "What is a %s? How does %s work in the game system? Rules and mechanics for %s.",
            itemType, itemType, itemType
        );

        // Create filter for system-specific search
        Filter.Expression filterExpression = new FilterExpressionBuilder()
            .eq("foundry_system", systemId)
            .build();

        // Build search request with filter
        SearchRequest searchRequest = SearchRequest.builder()
            .query(searchQuery)
            .topK(topK)
            .filterExpression(filterExpression)
            .build();

        // Perform similarity search
        List<Document> documents = vectorStore.similaritySearch(searchRequest);

        if (documents.isEmpty()) {
            log.debug("No documents found for item type '{}' in system '{}'", itemType, systemId);
            return "";
        }

        // Format the documents into readable context
        return documents.stream()
            .map(doc -> {
                StringBuilder sb = new StringBuilder();
                
                // Add source information if available
                Object sourceObj = doc.getMetadata().get("source");
                if (sourceObj != null) {
                    sb.append("[Source: ").append(sourceObj.toString()).append("]\n");
                }
                
                sb.append(doc.getText());
                return sb.toString();
            })
            .collect(Collectors.joining("\n\n"));
    }

    /**
     * Search for general context about character creation using VectorStore
     */
    public String searchCharacterCreationContext(String characterConcept, String systemId, int topK) {
        log.info("Searching character creation context for: '{}' in system: {}", characterConcept, systemId);

        try {
            // Create search query for character creation
            String searchQuery = "Character creation rules and guidelines: " + characterConcept;

            // Create filter for system-specific search
            Filter.Expression filterExpression = new FilterExpressionBuilder()
                .eq("foundry_system", systemId)
                .build();

            // Build search request
            SearchRequest searchRequest = SearchRequest.builder()
                .query(searchQuery)
                .topK(topK)
                .filterExpression(filterExpression)
                .build();

            // Perform similarity search
            List<Document> documents = vectorStore.similaritySearch(searchRequest);

            if (documents.isEmpty()) {
                log.debug("No character creation context found");
                return "";
            }

            StringBuilder context = new StringBuilder();
            context.append("=== CHARACTER CREATION GUIDELINES FROM RULES ===\n\n");

            for (Document doc : documents) {
                Object sourceObj = doc.getMetadata().get("source");
                if (sourceObj != null) {
                    context.append("[Source: ").append(sourceObj.toString()).append("]\n");
                }
                context.append(doc.getText()).append("\n\n");
            }

            log.info("Character creation context retrieved: {} characters", context.length());
            return context.toString();

        } catch (Exception e) {
            log.error("Failed to search character creation context", e);
            return "";
        }
    }
}

