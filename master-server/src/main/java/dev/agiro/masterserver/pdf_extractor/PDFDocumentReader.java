package dev.agiro.masterserver.pdf_extractor;

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
import java.util.List;
import java.util.Optional;

@Component
public class PDFDocumentReader {

    /**
     * Overload accepting byte[] — safe for @Async usage.
     */
    public List<Document> getDocsFromPdfWithCatalog(byte[] pdfBytes, String foundrySystem) {
        Resource resource = new ByteArrayResource(pdfBytes);

        DocumentReader pdfReader = tryParagraphReader(resource, foundrySystem)
                .orElseGet(() -> getPageReader(resource, foundrySystem));

        return pdfReader.read();
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
}