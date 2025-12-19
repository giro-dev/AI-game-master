package dev.agiro.masterserver.pdf_extractor;


import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DocumentTransformer that detects tables in document content and converts them to Markdown format.
 * Also adds metadata to identify documents containing tables.
 */
public class TableToMarkdownDocumentTransformer implements DocumentTransformer {

    private static final String TABLE_METADATA_KEY = "contains_table";
    private static final String TABLE_COUNT_KEY = "table_count";
    private static final String TABLE_FORMAT_KEY = "table_format";

    // Pattern to detect various table formats (tab-separated, pipe-separated, etc.)
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(?m)^(?:[^\\n]*[|\\t]){2,}[^\\n]*$(?:\\n(?:[^\\n]*[|\\t]){2,}[^\\n]*$)+"
    );

    // Pattern for detecting CSV-like tables
    private static final Pattern CSV_TABLE_PATTERN = Pattern.compile(
            "(?m)^(?:[^,\\n]+,){2,}[^,\\n]+$(?:\\n(?:[^,\\n]+,){2,}[^,\\n]+$)+"
    );

    private final boolean preserveOriginal;
    private final int minRows;
    private final int minColumns;

    /**
     * Creates a new TableToMarkdownDocumentTransformer with default settings.
     */
    public TableToMarkdownDocumentTransformer() {
        this(false, 2, 2);
    }

    /**
     * Creates a new TableToMarkdownDocumentTransformer with custom settings.
     *
     * @param preserveOriginal if true, keeps the original table format alongside the Markdown version
     * @param minRows minimum number of rows to consider something a table
     * @param minColumns minimum number of columns to consider something a table
     */
    public TableToMarkdownDocumentTransformer(boolean preserveOriginal, int minRows, int minColumns) {
        this.preserveOriginal = preserveOriginal;
        this.minRows = minRows;
        this.minColumns = minColumns;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        return transform(documents);
    }

    @Override
    public List<Document> transform(List<Document> documents) {
        List<Document> transformedDocuments = new ArrayList<>();

        for (Document document : documents) {
            String content = document.getText();
            Map<String, Object> metadata = new HashMap<>(document.getMetadata());

            // Detect and convert tables
            TableConversionResult result = detectAndConvertTables(content);

            if (result.tablesFound > 0) {
                metadata.put(TABLE_METADATA_KEY, true);
                metadata.put(TABLE_COUNT_KEY, result.tablesFound);
                metadata.put(TABLE_FORMAT_KEY, "markdown");

                Document transformedDoc = Document.builder()
                        .id(document.getId())
                        .text(result.convertedContent)
                        .metadata(metadata)
                        .build();

                transformedDocuments.add(transformedDoc);
            } else {
                // No tables found, add original document
                transformedDocuments.add(document);
            }
        }

        return transformedDocuments;
    }

    /**
     * Detects tables in content and converts them to Markdown format.
     */
    private TableConversionResult detectAndConvertTables(String content) {
        int tablesFound = 0;
        String processedContent = content;

        // Try to detect pipe-separated tables first
        Matcher pipeMatcher = TABLE_PATTERN.matcher(content);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;

        while (pipeMatcher.find()) {
            String tableText = pipeMatcher.group();

            if (isValidTable(tableText, '|')) {
                result.append(content, lastEnd, pipeMatcher.start());

                if (preserveOriginal) {
                    result.append("**Original Table:**\n```\n");
                    result.append(tableText);
                    result.append("\n```\n\n**Markdown Table:**\n");
                }

                String markdownTable = convertPipeTableToMarkdown(tableText);
                result.append(markdownTable);

                lastEnd = pipeMatcher.end();
                tablesFound++;
            }
        }

        if (tablesFound > 0) {
            result.append(content.substring(lastEnd));
            processedContent = result.toString();
        } else {
            // Try CSV format
            Matcher csvMatcher = CSV_TABLE_PATTERN.matcher(content);
            result = new StringBuilder();
            lastEnd = 0;

            while (csvMatcher.find()) {
                String tableText = csvMatcher.group();

                if (isValidTable(tableText, ',')) {
                    result.append(content, lastEnd, csvMatcher.start());

                    if (preserveOriginal) {
                        result.append("**Original Table:**\n```\n");
                        result.append(tableText);
                        result.append("\n```\n\n**Markdown Table:**\n");
                    }

                    String markdownTable = convertCsvTableToMarkdown(tableText);
                    result.append(markdownTable);

                    lastEnd = csvMatcher.end();
                    tablesFound++;
                }
            }

            if (tablesFound > 0) {
                result.append(content.substring(lastEnd));
                processedContent = result.toString();
            }
        }

        return new TableConversionResult(processedContent, tablesFound);
    }

    /**
     * Validates if the detected text is actually a table based on row and column counts.
     */
    private boolean isValidTable(String tableText, char delimiter) {
        String[] lines = tableText.split("\n");

        if (lines.length < minRows) {
            return false;
        }

        // Check first line for minimum columns
        String[] firstLineCells = lines[0].split(Pattern.quote(String.valueOf(delimiter)));
        int expectedColumns = firstLineCells.length;

        if (expectedColumns < minColumns) {
            return false;
        }

        // Validate that all rows have similar column count
        for (String line : lines) {
            String[] cells = line.split(Pattern.quote(String.valueOf(delimiter)));
            // Allow some variance in column count (±1)
            if (Math.abs(cells.length - expectedColumns) > 1) {
                return false;
            }
        }

        return true;
    }

    /**
     * Converts pipe-separated table to Markdown format.
     */
    private String convertPipeTableToMarkdown(String tableText) {
        String[] lines = tableText.split("\n");
        StringBuilder markdown = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // Clean up the line
            if (!line.startsWith("|")) {
                line = "| " + line;
            }
            if (!line.endsWith("|")) {
                line = line + " |";
            }

            markdown.append(line).append("\n");

            // Add separator after first row (header)
            if (i == 0) {
                String[] cells = line.split("\\|");
                markdown.append("|");
                for (int j = 1; j < cells.length - 1; j++) {
                    markdown.append(" --- |");
                }
                markdown.append("\n");
            }
        }

        return markdown.toString();
    }

    /**
     * Converts CSV table to Markdown format.
     */
    private String convertCsvTableToMarkdown(String tableText) {
        String[] lines = tableText.split("\n");
        StringBuilder markdown = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String[] cells = lines[i].split(",");

            markdown.append("|");
            for (String cell : cells) {
                markdown.append(" ").append(cell.trim()).append(" |");
            }
            markdown.append("\n");

            // Add separator after first row (header)
            if (i == 0) {
                markdown.append("|");
                for (int j = 0; j < cells.length; j++) {
                    markdown.append(" --- |");
                }
                markdown.append("\n");
            }
        }

        return markdown.toString();
    }

    /**
     * Result class for table conversion operations.
     */
    private static class TableConversionResult {
        final String convertedContent;
        final int tablesFound;

        TableConversionResult(String convertedContent, int tablesFound) {
            this.convertedContent = convertedContent;
            this.tablesFound = tablesFound;
        }
    }
}