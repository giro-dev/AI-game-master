package dev.agiro.masterserver.controller;

import dev.agiro.masterserver.dto.MetadataField;
import dev.agiro.masterserver.dto.RagContextRequest;
import dev.agiro.masterserver.dto.RagSearchResponse;
import dev.agiro.masterserver.embedding.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagSearchController {

    private final RagService ragService;


    /**
     * Search for similar document chunks
     * GET /api/rag/search?query=how does attack of opportunity work&topK=5
     */
    @GetMapping("/search")
    public ResponseEntity<List<RagSearchResponse>> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(required = false) String foundrySystem,
            @RequestParam(required = false) String sourceDocument) {

        List<Document> results;

        if (foundrySystem != null && !foundrySystem.isBlank()) {
            results = ragService.searchSimilarBySystem(query, foundrySystem, topK);
        } else if (sourceDocument != null && !sourceDocument.isBlank()) {
            results = ragService.searchSimilarByDocument(query, sourceDocument, topK);
        } else {
            results = ragService.searchSimilar(query, topK);
        }

        List<RagSearchResponse> response = results.stream()
                .map(RagSearchResponse::fromDocument)
                .toList();

        return ResponseEntity.ok(response);
    }


    /**
     * Search with similarity threshold
     * GET /api/rag/search/threshold?query=...&maxDistance=0.5&topK=10
     */
    @GetMapping("/search/threshold")
    public ResponseEntity<List<RagSearchResponse>> searchWithThreshold(
            @RequestParam String query,
            @RequestParam(defaultValue = "0.5") double maxDistance,
            @RequestParam(defaultValue = "10") int topK) {

        List<Document> results = ragService.searchSimilarWithThreshold(query, maxDistance, topK);

        List<RagSearchResponse> response = results.stream()
                .map(RagSearchResponse::fromDocument)
                .toList();

        return ResponseEntity.ok(response);
    }
}

