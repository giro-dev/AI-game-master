package dev.agiro.masterserver.pdf_extractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.embedding.EmbeddingDto;
import dev.agiro.masterserver.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class PdfProcessor {

    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<EmbeddingDto> processPdf(MultipartFile file) throws IOException {
        List<EmbeddingDto> results = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(file.getInputStream())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String fullText = stripper.getText(doc);

            // split into sections using simple heuristics (numbers like 1., 1.1, or ALL CAPS headings)
            List<Section> sections = splitIntoSections(fullText);

            for (Section s : sections) {
                float[] vec = embeddingService.createEmbedding(s.getTitle() + "\n" + s.getBody());
                Map<String, Object> meta = new HashMap<>();
                meta.put("title", s.getTitle());
                meta.put("page", s.getPage());
                meta.put("type", "text");
                results.add(new EmbeddingDto(vec, meta));
            }

            // extract images
            List<ImageData> images = extractImages(doc);
            for (ImageData img : images) {
                float[] vec = embeddingService.createEmbedding("image extracted from page " + img.getPage());
                Map<String, Object> meta = new HashMap<>();
                meta.put("type", "image");
                meta.put("page", img.getPage());
                meta.put("filename", img.getFilename());
                results.add(new EmbeddingDto(vec, meta));
            }

            // table extraction: placeholder - implement tabula or custom parsing for real tables
            // For now tables can be detected by repeated separators or multi-column text heuristics:
            List<Section> tables = detectTables(sections);
            for (Section t : tables) {
                float[] vec = embeddingService.createEmbedding(t.getTitle() + "\n" + t.getBody());
                Map<String, Object> meta = new HashMap<>();
                meta.put("type", "table");
                meta.put("title", t.getTitle());
                meta.put("page", t.getPage());
                results.add(new EmbeddingDto(vec, meta));
            }
        }
        return results;
    }

    private List<Section> splitIntoSections(String text) {
        List<Section> out = new ArrayList<>();
        // simple split by heading patterns: lines starting with digits or all-caps lines
        String[] lines = text.split("\\r?\\n");
        Pattern headingPattern = Pattern.compile("^(\\d+(\\.\\d+)*)\\s+(.+)$"); // 1. or 1.1 heading
        StringBuilder currentBody = new StringBuilder();
        String currentTitle = "Introduction";
        int currentPage = 1;
        for (String line : lines) {
            Matcher m = headingPattern.matcher(line.trim());
            if (m.matches() || line.trim().equals(line.trim().toUpperCase()) && line.trim().length() > 3) {
                // save previous
                if (currentBody.length() > 0) {
                    out.add(new Section(currentTitle, currentBody.toString().trim(), currentPage));
                }
                currentTitle = m.matches() ? m.group(3) : line.trim();
                currentBody = new StringBuilder();
            } else {
                if (!line.trim().isEmpty()) {
                    currentBody.append(line).append("\n");
                }
            }
        }
        if (currentBody.length() > 0) {
            out.add(new Section(currentTitle, currentBody.toString().trim(), currentPage));
        }
        return out;
    }

    private List<ImageData> extractImages(PDDocument doc) throws IOException {
        List<ImageData> images = new ArrayList<>();
        int pageIndex = 0;
        for (PDPage page : doc.getPages()) {
            pageIndex++;
            PDResources resources = page.getResources();
            if (resources == null) continue;
            Iterable<COSName> xObjectNames = resources.getXObjectNames();
            for (COSName name : xObjectNames) {
                try {
                    var xobj = resources.getXObject(name);
                    if (xobj instanceof PDImageXObject) {
                        PDImageXObject img = (PDImageXObject) xobj;
                        String filename = "pdf_image_p" + pageIndex + "_" + name.getName() + ".png";
                        File out = File.createTempFile("pdf_img_", ".png");
                        FileOutputStream fileOutputStream = new FileOutputStream(out);
                        fileOutputStream.write(img.createInputStream().readAllBytes());
                        images.add(new ImageData(out.getAbsolutePath(), pageIndex, filename));
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return images;
    }

    private List<Section> detectTables(List<Section> sections) {
        List<Section> tables = new ArrayList<>();
        for (Section s : sections) {
            // naive heuristic: many consecutive lines with multiple columns (tabs or multiple spaces)
            long linesWithCols = Arrays.stream(s.getBody().split("\\r?\\n"))
                    .filter(l -> l.trim().contains("  ") || l.trim().contains("\t"))
                    .count();
            if (linesWithCols > 3) {
                tables.add(new Section("Table: " + s.getTitle(), s.getBody(), s.getPage()));
            }
        }
        return tables;
    }
}