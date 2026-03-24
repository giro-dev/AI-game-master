package dev.agiro.masterserver.service;

import dev.agiro.masterserver.dto.ReferenceCharacterDto;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch.core.*;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists reference characters in OpenSearch with an in-memory read-through cache.
 * <p>
 * Index: {@code reference-characters}
 * Document ID: {@code {systemId}:{actorType}}
 */
@Slf4j
@Repository
public class ReferenceCharacterRepository {

    private static final String INDEX = "reference-characters";

    private final OpenSearchClient openSearchClient;

    /** Hot cache so reads never hit OpenSearch during generation. */
    private final Map<String, ReferenceCharacterDto> cache = new ConcurrentHashMap<>();

    public ReferenceCharacterRepository(OpenSearchClient openSearchClient) {
        this.openSearchClient = openSearchClient;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @PostConstruct
    void init() {
        ensureIndex();
        warmCache();
    }

    // ── Public API ───────────────────────────────────────────────────────

    public void save(ReferenceCharacterDto ref) {
        String id = docId(ref.getSystemId(), ref.getActorType());
        try {
            openSearchClient.index(IndexRequest.of(i -> i
                    .index(INDEX)
                    .id(id)
                    .document(ref)
                    .refresh(Refresh.True)
            ));
            cache.put(id, ref);
            log.info("Saved reference character '{}' → {} (index={})", ref.getLabel(), id, INDEX);
        } catch (Exception e) {
            log.error("Failed to save reference character '{}' to OpenSearch: {}", id, e.getMessage(), e);
            // Still keep in cache so the current session works
            cache.put(id, ref);
        }
    }

    public Optional<ReferenceCharacterDto> find(String systemId, String actorType) {
        String id = docId(systemId, actorType);

        // Cache hit
        ReferenceCharacterDto cached = cache.get(id);
        if (cached != null) return Optional.of(cached);

        // Cache miss → try OpenSearch
        try {
            GetResponse<ReferenceCharacterDto> response = openSearchClient.get(
                    GetRequest.of(g -> g.index(INDEX).id(id)),
                    ReferenceCharacterDto.class
            );
            if (response.found() && response.source() != null) {
                cache.put(id, response.source());
                return Optional.of(response.source());
            }
        } catch (Exception e) {
            log.warn("OpenSearch lookup failed for reference character '{}': {}", id, e.getMessage());
        }
        return Optional.empty();
    }

    public void delete(String systemId, String actorType) {
        String id = docId(systemId, actorType);
        cache.remove(id);
        try {
            openSearchClient.delete(DeleteRequest.of(d -> d
                    .index(INDEX)
                    .id(id)
                    .refresh(Refresh.True)
            ));
            log.info("Deleted reference character from OpenSearch: {}", id);
        } catch (Exception e) {
            log.warn("Failed to delete reference character '{}' from OpenSearch: {}", id, e.getMessage());
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private static String docId(String systemId, String actorType) {
        return systemId + ":" + actorType;
    }

    /**
     * Create the index if it doesn't exist (no vector embeddings needed — plain JSON docs).
     */
    private void ensureIndex() {
        try {
            boolean exists = openSearchClient.indices()
                    .exists(ExistsRequest.of(e -> e.index(INDEX)))
                    .value();
            if (!exists) {
                openSearchClient.indices().create(c -> c
                        .index(INDEX)
                        .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                );
                log.info("Created OpenSearch index '{}'", INDEX);
            }
        } catch (Exception e) {
            log.warn("Could not ensure index '{}': {}", INDEX, e.getMessage());
        }
    }

    /**
     * On startup, load all reference characters into the in-memory cache.
     */
    private void warmCache() {
        try {
            SearchResponse<ReferenceCharacterDto> response = openSearchClient.search(s -> s
                            .index(INDEX)
                            .size(200)
                            .query(q -> q.matchAll(m -> m)),
                    ReferenceCharacterDto.class
            );
            int loaded = 0;
            for (var hit : response.hits().hits()) {
                if (hit.id() != null && hit.source() != null) {
                    cache.put(hit.id(), hit.source());
                    loaded++;
                }
            }
            log.info("Warmed reference-character cache: {} entries from '{}'", loaded, INDEX);
        } catch (Exception e) {
            log.warn("Could not warm reference-character cache from OpenSearch: {}", e.getMessage());
        }
    }
}

