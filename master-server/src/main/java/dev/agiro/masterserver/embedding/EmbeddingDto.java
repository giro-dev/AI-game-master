package dev.agiro.masterserver.embedding;
import lombok.Data;

import java.util.Map;


public record EmbeddingDto (float[] vector,
                            Map<String, Object> metadata){
}
