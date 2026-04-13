package dev.agiro.masterserver.pdf_extractor;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@Slf4j
@Component
public class PDFDocumentReader {

    /**
     * Overload accepting byte[] — safe for @Async usage.
     */
    List<Document> getDocsFromPdfWithCatalog(byte[] pdfBytes, String foundrySystem) {
        Resource resource = new ByteArrayResource(pdfBytes);

        // 1) Try paragraph-aware reader (uses PDF catalog / TOC)
        Optional<List<Document>> paragraphDocs = tryParagraphReader(resource);
        if (paragraphDocs.isPresent()) return paragraphDocs.get();

        // 2) Try Spring AI layout-aware page reader
        try {
            List<Document> docs = getPageReader(resource).read();
            log.info("PDF read with Spring AI PagePdfDocumentReader: {} docs", docs.size());
            return docs;
        } catch (Exception e) {
            log.warn("Spring AI PagePdfDocumentReader failed ({}), falling back to plain PDFBox extraction",
                    e.getMessage());
        }

        // 3) Fallback: plain PDFBox PDFTextStripper (page-by-page, no layout bugs)
        return fallbackPlainExtraction(pdfBytes);
    }

    /**
     * Original overload for synchronous callers that still use MultipartFile.
     */
    List<Document> getDocsFromPdfWithCatalog(MultipartFile file, String foundrySystem) {
        try {
            return getDocsFromPdfWithCatalog(file.getBytes(), foundrySystem);
        } catch (IOException e) {
            throw new PDFNotProcessable("Could not read PDF file", e);
        }
    }

    // ─── internal helpers ────────────────────────────────────────────────

    private DocumentReader getPageReader(Resource resource) {
        return new PagePdfDocumentReader(resource,
                PdfDocumentReaderConfig.builder()
                        .withPageTopMargin(0)
                        .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                                .withNumberOfTopTextLinesToDelete(0)
                                .build())
                        .withPagesPerDocument(1)
                        .build()
        );
    }

    private static Optional<List<Document>> tryParagraphReader(Resource resource) {
        try {
            ParagraphPdfDocumentReader pdfReader = new ParagraphPdfDocumentReader(resource,
                    PdfDocumentReaderConfig.builder()
                            .withPageTopMargin(0)
                            .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                                    .withNumberOfTopTextLinesToDelete(0)
                                    .build())
                            .withPagesPerDocument(1)
                            .build()
            );
            List<Document> docs = pdfReader.read();
            log.info("PDF read with ParagraphPdfDocumentReader: {} docs", docs.size());
            return Optional.of(docs);
        } catch (Exception e) {
            log.debug("ParagraphPdfDocumentReader not usable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Robust fallback using plain Apache PDFBox {@link PDFTextStripper}.
     * Extracts text page-by-page; skips individual pages that fail.
     * Avoids the buggy {@code ForkPDFLayoutTextStripper} comparator entirely.
     */
    private List<Document> fallbackPlainExtraction(byte[] pdfBytes) {
        List<Document> docs = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            int totalPages = pdf.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();

            for (int page = 1; page <= totalPages; page++) {
                try {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    String text = stripper.getText(pdf);
                    if (text != null && !text.isBlank()) {
                        Map<String, Object> meta = new HashMap<>();
                        meta.put("page_number", page);
                        meta.put("total_pages", totalPages);
                        meta.put("extraction_method", "pdfbox_plain");
                        docs.add(new Document(text, meta));
                    }
                } catch (Exception e) {
                    log.warn("Failed to extract page {} – skipping: {}", page, e.getMessage());
                }
            }
            log.info("Fallback PDFBox extraction complete: {} pages extracted out of {}", docs.size(), totalPages);
        } catch (IOException e) {
            throw new PDFNotProcessable("Plain PDFBox extraction failed entirely", e);
        }
        return docs;
    }
}