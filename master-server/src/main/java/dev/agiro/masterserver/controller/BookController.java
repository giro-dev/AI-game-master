package dev.agiro.masterserver.controller;

import dev.agiro.masterserver.dto.BookDto;
import dev.agiro.masterserver.pdf_extractor.IngestionPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * REST controller for book/document management.
 * Replaces the basic {@code /api/pdf/upload} endpoint with a richer lifecycle:
 * upload → async ingestion pipeline → query books per world → delete.
 */
@Slf4j
@RestController
@RequestMapping("/api/books")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class BookController {

    private final IngestionPipeline ingestionPipeline;

    /**
     * Upload a PDF and start the async ingestion pipeline.
     * Returns immediately with a {@code bookId}; progress is reported via WebSocket.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadBook(
            @RequestParam("file") MultipartFile file,
            @RequestParam("worldId") String worldId,
            @RequestParam("foundrySystem") String foundrySystem,
            @RequestParam(value = "bookTitle", required = false) String bookTitle,
            @RequestParam(value = "sessionId", required = false) String sessionId) {

        if (file.isEmpty() || !MediaType.APPLICATION_PDF_VALUE.equals(file.getContentType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid file. Please upload a PDF."));
        }

        String title = bookTitle != null ? bookTitle : Objects.requireNonNull(file.getOriginalFilename());
        String fileName = file.getOriginalFilename();

        // Read bytes NOW (before the request/multipart is cleaned up)
        byte[] pdfBytes;
        try {
            pdfBytes = file.getBytes();
        } catch (java.io.IOException e) {
            log.error("Failed to read uploaded file", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to read uploaded file"));
        }

        // Fire-and-forget — progress via WebSocket
        ingestionPipeline.ingestAsync(pdfBytes, fileName, worldId, foundrySystem, title, sessionId);

        log.info("Ingestion started for '{}' (world={}, system={})", title, worldId, foundrySystem);

        return ResponseEntity.accepted().body(Map.of(
                "message", "Ingestion started",
                "fileName", Objects.requireNonNull(fileName),
                "worldId", worldId,
                "foundrySystem", foundrySystem,
                "bookTitle", title
        ));
    }

    /**
     * List all books associated with a given world.
     */
    @GetMapping("/{worldId}")
    public ResponseEntity<List<BookDto>> getBooks(@PathVariable String worldId) {
        return ResponseEntity.ok(ingestionPipeline.getBooksByWorld(worldId));
    }

    /**
     * Get the status of a specific book.
     */
    @GetMapping("/status/{bookId}")
    public ResponseEntity<?> getBookStatus(@PathVariable String bookId) {
        return ingestionPipeline.getBook(bookId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete a book and its chunks.
     */
    @DeleteMapping("/{bookId}")
    public ResponseEntity<Map<String, String>> deleteBook(@PathVariable String bookId) {
        ingestionPipeline.deleteBook(bookId);
        return ResponseEntity.ok(Map.of("message", "Book deleted", "bookId", bookId));
    }
}

