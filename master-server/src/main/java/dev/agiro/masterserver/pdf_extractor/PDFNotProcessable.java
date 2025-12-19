package dev.agiro.masterserver.pdf_extractor;

import java.io.IOException;

public class PDFNotProcessable extends RuntimeException {
    public PDFNotProcessable(String message) {
        super(message);
    }

    public PDFNotProcessable(String couldNotReadPdfFile, IOException e) {
        super(couldNotReadPdfFile, e);
    }
}
