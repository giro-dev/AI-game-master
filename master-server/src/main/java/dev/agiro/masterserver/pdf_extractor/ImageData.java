package dev.agiro.masterserver.pdf_extractor;

public class ImageData {
    private String filename;
    private int page;
    private String path;

    public ImageData() {}

    public ImageData(String path, int page, String filename) {
        this.path = path;
        this.page = page;
        this.filename = filename;
    }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
}