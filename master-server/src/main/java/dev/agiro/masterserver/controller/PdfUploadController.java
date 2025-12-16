package dev.agiro.masterserver.controller;

import dev.agiro.masterserver.embedding.EmbeddingDto;
import dev.agiro.masterserver.pdf_extractor.PdfProcessingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/pdf")
@RequiredArgsConstructor
public class PdfUploadController {

    private final PdfProcessingService pdfProcessingService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "foundrySystem", required = false) String foundrySystem) throws IOException {

        if (file.isEmpty() || !MediaType.APPLICATION_PDF_VALUE.equals(file.getContentType())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid file. Please upload a PDF file."
            ));
        }

        List<EmbeddingDto> embeddings = pdfProcessingService.processPdf(file, foundrySystem);

        return ResponseEntity.ok(Map.of(
                "message", "PDF processed successfully",
                "filename", Objects.requireNonNull(file.getOriginalFilename()),
                "foundrySystem", foundrySystem != null ? foundrySystem : "none",
                "chunksCreated", embeddings.size()
        ));
    }
}