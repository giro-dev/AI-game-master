package dev.agiro.masterserver.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.CorrectionDto;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persists GM corrections to the {@code system-corrections} OpenSearch index.
 * Each document is one correction event keyed by an auto-assigned UUID.
 */
@Slf4j
@Repository
public class CorrectionRepository {

    static final String INDEX = "system-corrections";

    private final OpenSearchClient openSearchClient;
    private final ObjectMapper objectMapper;

    public CorrectionRepository(OpenSearchClient openSearchClient, ObjectMapper objectMapper) {
        this.openSearchClient = openSearchClient;
        this.objectMapper = objectMapper;
        ensureIndex();
    }

    /**
     * Store a correction document and return its assigned ID.
     */
    public String save(CorrectionDto correction) {
        try {
            Map<String, Object> doc = objectMapper.convertValue(correction, Map.class);
            IndexResponse response = openSearchClient.index(i -> i
                    .index(INDEX)
                    .document(doc));
            log.debug("Saved correction '{}' for system '{}'", response.id(), correction.getSystemId());
            return response.id();
        } catch (Exception e) {
            log.warn("Failed to save correction for '{}': {}", correction.getSystemId(), e.getMessage());
            return "unknown";
        }
    }

    /**
     * Return recent corrections for a system (up to {@code limit}), newest first.
     */
    public List<CorrectionDto> findBySystemId(String systemId, int limit) {
        try {
            SearchResponse<Map> response = openSearchClient.search(s -> s
                    .index(INDEX)
                    .query(Query.of(q -> q
                            .term(t -> t.field("systemId").value(v -> v.stringValue(systemId)))))
                    .sort(so -> so.field(f -> f.field("timestamp").order(
                            org.opensearch.client.opensearch._types.SortOrder.Desc)))
                    .size(limit),
                    Map.class);

            List<CorrectionDto> results = new ArrayList<>();
            for (var hit : response.hits().hits()) {
                if (hit.source() != null) {
                    results.add(objectMapper.convertValue(hit.source(), CorrectionDto.class));
                }
            }
            return results;
        } catch (Exception e) {
            log.debug("Could not query corrections for '{}': {}", systemId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Count how many corrections exist for a given system.
     */
    public long countBySystemId(String systemId) {
        try {
            var response = openSearchClient.count(c -> c
                    .index(INDEX)
                    .query(Query.of(q -> q
                            .term(t -> t.field("systemId").value(v -> v.stringValue(systemId))))));
            return response.count();
        } catch (Exception e) {
            log.debug("Could not count corrections for '{}': {}", systemId, e.getMessage());
            return 0L;
        }
    }

    private void ensureIndex() {
        try {
            boolean exists = openSearchClient.indices().exists(e -> e.index(INDEX)).value();
            if (!exists) {
                openSearchClient.indices().create(c -> c
                        .index(INDEX)
                        .settings(s -> s
                                .numberOfShards("1")
                                .numberOfReplicas("0")));
                log.info("Created OpenSearch index '{}'", INDEX);
            }
        } catch (Exception e) {
            log.warn("Could not ensure index '{}': {}", INDEX, e.getMessage());
        }
    }
}
