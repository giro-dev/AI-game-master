package dev.agiro.masterserver.embedding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunkEntity, UUID> {

    /**
     * Find similar chunks using cosine distance (pgvector <=> operator)
     * Lower distance = more similar
     */
    @Query(value = """
            SELECT * FROM document_chunks
            ORDER BY embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<DocumentChunkEntity> findSimilarChunks(
            @Param("embedding") String embedding,
            @Param("limit") int limit
    );

    /**
     * Find similar chunks filtered by foundry system
     */
    @Query(value = """
            SELECT * FROM document_chunks
            WHERE foundry_system = :foundrySystem
            ORDER BY embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<DocumentChunkEntity> findSimilarChunksBySystem(
            @Param("embedding") String embedding,
            @Param("foundrySystem") String foundrySystem,
            @Param("limit") int limit
    );

    /**
     * Find similar chunks filtered by source document
     */
    @Query(value = """
            SELECT * FROM document_chunks
            WHERE source_document = :sourceDocument
            ORDER BY embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<DocumentChunkEntity> findSimilarChunksByDocument(
            @Param("embedding") String embedding,
            @Param("sourceDocument") String sourceDocument,
            @Param("limit") int limit
    );

    /**
     * Find similar chunks with distance threshold
     */
    @Query(value = """
            SELECT *, (embedding <=> CAST(:embedding AS vector)) AS distance
            FROM document_chunks
            WHERE (embedding <=> CAST(:embedding AS vector)) < :maxDistance
            ORDER BY embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<DocumentChunkEntity> findSimilarChunksWithThreshold(
            @Param("embedding") String embedding,
            @Param("maxDistance") double maxDistance,
            @Param("limit") int limit
    );

    List<DocumentChunkEntity> findBySourceDocument(String sourceDocument);

    List<DocumentChunkEntity> findByFoundrySystem(String foundrySystem);

    void deleteBySourceDocument(String sourceDocument);
}

