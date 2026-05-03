package dev.agiro.masterserver.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.SystemProfileDto;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

/**
 * OpenSearch-backed persistence for {@link SystemProfileDto}.
 * Each profile is stored as a single document keyed by {@code systemId}.
 */
@Slf4j
@Repository
public class SystemProfileRepository {

    static final String INDEX = "system-profiles";

    private final OpenSearchClient openSearchClient;
    private final ObjectMapper objectMapper;

    public SystemProfileRepository(OpenSearchClient openSearchClient, ObjectMapper objectMapper) {
        this.openSearchClient = openSearchClient;
        this.objectMapper = objectMapper;
        ensureIndex();
    }

    public void save(SystemProfileDto profile) {
        try {
            Map<String, Object> doc = objectMapper.convertValue(profile, Map.class);
            IndexResponse response = openSearchClient.index(i -> i
                    .index(INDEX)
                    .id(profile.getSystemId())
                    .document(doc));
            log.debug("Saved system profile '{}': result={}", profile.getSystemId(), response.result().jsonValue());
        } catch (Exception e) {
            log.warn("Failed to save system profile '{}': {}", profile.getSystemId(), e.getMessage());
        }
    }

    public Optional<SystemProfileDto> find(String systemId) {
        try {
            GetResponse<Map> response = openSearchClient.get(g -> g
                    .index(INDEX)
                    .id(systemId),
                    Map.class);

            if (!response.found() || response.source() == null) {
                return Optional.empty();
            }

            SystemProfileDto profile = objectMapper.convertValue(response.source(), SystemProfileDto.class);
            return Optional.of(profile);

        } catch (Exception e) {
            log.debug("System profile '{}' not found in OpenSearch: {}", systemId, e.getMessage());
            return Optional.empty();
        }
    }

    public void delete(String systemId) {
        try {
            openSearchClient.delete(d -> d.index(INDEX).id(systemId));
            log.debug("Deleted system profile '{}'", systemId);
        } catch (Exception e) {
            log.warn("Failed to delete system profile '{}': {}", systemId, e.getMessage());
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
