package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagSearchResponse {
    private String id;
    private String content;
    private Map<String, Object> metadata;

    public static RagSearchResponse fromDocument(Document document) {
        return new RagSearchResponse(
                document.getId(),
                document.getFormattedContent(),
                document.getMetadata()
        );
    }
}

