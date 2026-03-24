package dev.agiro.masterserver.dto;

import lombok.Data;

@Data
public class BookUploadRequest {
    private String worldId;
    private String foundrySystem;
    private String bookTitle;
}

