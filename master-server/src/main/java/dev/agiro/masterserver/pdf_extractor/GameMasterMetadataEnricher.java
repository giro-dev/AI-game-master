package dev.agiro.masterserver.pdf_extractor;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;

import java.util.List;

public class GameMasterMetadataEnricher implements DocumentTransformer {
    private static final String FILE_NAME_METADATA_KEY = "file_name";
    private static final String GAME_SYSTEM_METADATA_KEY = "game_system";
    private final String fileName;
    private final String gameSystem;

    public GameMasterMetadataEnricher(String fileName, String gameSystem) {
        this.fileName = fileName;
        this.gameSystem = gameSystem;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        documents.forEach(document -> {
            document.getMetadata().put(FILE_NAME_METADATA_KEY, fileName);
            document.getMetadata().put(GAME_SYSTEM_METADATA_KEY, gameSystem);
        });
        return documents;
    }
}
