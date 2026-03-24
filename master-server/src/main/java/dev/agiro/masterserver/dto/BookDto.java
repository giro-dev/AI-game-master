package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookDto {
    private String bookId;
    private String worldId;
    private String foundrySystem;
    private String bookTitle;
    private String fileName;
    private Instant uploadDate;
    private int chunkCount;
    private int entityCount;
    private String status; // UPLOADING, CLASSIFYING, EXTRACTING, COMPLETED, FAILED
}

