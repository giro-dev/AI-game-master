package dev.agiro.masterserver.controller;

import dev.agiro.masterserver.dto.RagContextRequest;
import dev.agiro.masterserver.dto.RagSearchResponse;
import dev.agiro.masterserver.embedding.DocumentChunkEntity;
import dev.agiro.masterserver.embedding.RagService;
import lombok.RequiredArgsConstructor;
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

        List<DocumentChunkEntity> results;

        if (foundrySystem != null && !foundrySystem.isBlank()) {
            results = ragService.searchSimilarBySystem(query, foundrySystem, topK);
        } else if (sourceDocument != null && !sourceDocument.isBlank()) {
            results = ragService.searchSimilarByDocument(query, sourceDocument, topK);
        } else {
            results = ragService.searchSimilar(query, topK);
        }

        List<RagSearchResponse> response = results.stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(response);
    }

    /**
     * Get formatted context for LLM prompts (RAG retrieval)
     * POST /api/rag/context
     */
    @PostMapping("/context")
    public ResponseEntity<String> getContext(@RequestBody RagContextRequest request) {
        String context;

        if (request.getFoundrySystem() != null && !request.getFoundrySystem().isBlank()) {
            context = ragService.buildContextForQueryBySystem(
                    request.getQuery(),
                    request.getFoundrySystem(),
                    request.getTopK() != null ? request.getTopK() : 5
            );
        } else {
            context = ragService.buildContextForQuery(
                    request.getQuery(),
                    request.getTopK() != null ? request.getTopK() : 5
            );
        }

        return ResponseEntity.ok(context);
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

        List<DocumentChunkEntity> results = ragService.searchSimilarWithThreshold(query, maxDistance, topK);

        List<RagSearchResponse> response = results.stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(response);
    }

    private RagSearchResponse toResponse(DocumentChunkEntity entity) {
        return RagSearchResponse.builder()
                .id(entity.getId())
                .content(entity.getContent())
                .title(entity.getTitle())
                .page(entity.getPage())
                .chunkType(entity.getChunkType())
                .sourceDocument(entity.getSourceDocument())
                .foundrySystem(entity.getFoundrySystem())
                .metadata(entity.getMetadata())
                .build();
    }
}

