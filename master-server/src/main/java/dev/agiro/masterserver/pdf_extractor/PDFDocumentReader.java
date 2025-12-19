package dev.agiro.masterserver.pdf_extractor;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
public class PDFDocumentReader {

    List<Document> getDocsFromPdfWithCatalog(MultipartFile file, String foundrySystem){


        DocumentReader pdfReader = tryParagraphReader(file, foundrySystem)
                .orElse(getPageReader(file, foundrySystem));

        return pdfReader.read();
    }

    private  DocumentReader getPageReader(MultipartFile file, String foundrySystem) {
        InputStreamResource pdfResource = createResource(file);
        return new PagePdfDocumentReader(pdfResource,
                PdfDocumentReaderConfig.builder()
                        .withPageTopMargin(0)
                        .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                                .withNumberOfTopTextLinesToDelete(0)
                                .build())
                        .withPagesPerDocument(1)
                        .build()
        );

    }

    private static Optional<DocumentReader> tryParagraphReader(MultipartFile file, String foundrySystem) {
        InputStreamResource pdfResource = createResource(file);
        try {
            ParagraphPdfDocumentReader pdfReader = new ParagraphPdfDocumentReader(pdfResource,
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
            // Log the exception if needed
            return Optional.empty();
        }
    }

    private static InputStreamResource createResource(MultipartFile file){
        try {
            return new InputStreamResource(file.getInputStream());
        } catch (IOException e) {
            throw new PDFNotProcessable("Could not read PDF file", e);
        }
    }
}