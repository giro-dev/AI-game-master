package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionEvent {
    private String bookId;
    private String worldId;
    private String status;   // STARTED, PARSING, CLASSIFYING, EXTRACTING, STORING, COMPLETED, FAILED
    private int progress;    // 0-100
    private String message;
    private Integer chunksProcessed;
    private Integer totalChunks;
    private Integer entitiesExtracted;
    private String error;
}

