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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class PDFDocumentReader {

    /**
     * Overload accepting byte[] — safe for @Async usage.
     */
    public List<Document> getDocsFromPdfWithCatalog(byte[] pdfBytes, String foundrySystem) {
        Resource resource = new ByteArrayResource(pdfBytes);

        // Try Spring AI readers first; fall back to plain PDFBox on failure
        // (covers the known ForkPDFLayoutTextStripper comparator bug).
        try {
            DocumentReader pdfReader = tryParagraphReader(resource, foundrySystem)
                    .orElseGet(() -> getPageReader(resource, foundrySystem));
            List<Document> docs = pdfReader.read();
            boolean hasText = docs.stream().anyMatch(d -> d.getText() != null && !d.getText().isBlank());
            if (hasText) return docs;
            log.warn("Spring AI PDF readers returned no text, falling back to plain PDFBox");
        } catch (Exception e) {
            log.warn("Spring AI PDF reader failed ({}), falling back to plain PDFBox", e.getMessage());
        }
        return readWithPlainPdfBox(pdfBytes);
    }

    /**
     * Original overload for synchronous callers that still use MultipartFile.
     */
    public List<Document> getDocsFromPdfWithCatalog(MultipartFile file, String foundrySystem) {
        try {
            return getDocsFromPdfWithCatalog(file.getBytes(), foundrySystem);
        } catch (IOException e) {
            throw new PDFNotProcessable("Could not read PDF file", e);
        }
    }

    private DocumentReader getPageReader(Resource resource, String foundrySystem) {
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

    private static Optional<DocumentReader> tryParagraphReader(Resource resource, String foundrySystem) {
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
            return Optional.of(pdfReader);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Fallback: use Apache PDFBox directly to extract text page-by-page.
     * This avoids the broken comparator in Spring AI's ForkPDFLayoutTextStripper.
     */
    private static List<Document> readWithPlainPdfBox(byte[] pdfBytes) {
        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pages = pdf.getNumberOfPages();
            List<Document> docs = new ArrayList<>(pages);
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(pdf);
                if (text != null && !text.isBlank()) {
                    docs.add(new Document(text, Map.of("page", p)));
                }
            }
            log.info("Plain PDFBox extracted {} non-empty pages out of {}", docs.size(), pages);
            return docs;
        } catch (IOException e) {
            throw new PDFNotProcessable("Could not read PDF with plain PDFBox", e);
        }
    }
}