package dev.agiro.masterserver.pdf_extractor;


public class Section {
    private String title;
    private String body;
    private int page;

    public Section() {}

    public Section(String title, String body, int page) {
        this.title = title;
        this.body = body;
        this.page = page;
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
}
