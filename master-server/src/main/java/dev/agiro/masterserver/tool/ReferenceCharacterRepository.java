package dev.agiro.masterserver.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.ReferenceCharacterDto;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

/**
 * OpenSearch-backed persistence for {@link ReferenceCharacterDto}.
 * Reference characters are keyed by {@code systemId + ":" + actorType}.
 */
@Slf4j
@Repository
public class ReferenceCharacterRepository {

    static final String INDEX = "reference-characters";

    private final OpenSearchClient openSearchClient;
    private final ObjectMapper objectMapper;

    public ReferenceCharacterRepository(OpenSearchClient openSearchClient, ObjectMapper objectMapper) {
        this.openSearchClient = openSearchClient;
        this.objectMapper = objectMapper;
        ensureIndex();
    }

    public void save(ReferenceCharacterDto ref) {
        String docId = docId(ref.getSystemId(), ref.getActorType());
        try {
            Map<String, Object> doc = objectMapper.convertValue(ref, Map.class);
            IndexResponse response = openSearchClient.index(i -> i
                    .index(INDEX)
                    .id(docId)
                    .document(doc));
            log.debug("Saved reference character '{}' ({}): result={}", ref.getLabel(), docId, response.result().jsonValue());
        } catch (Exception e) {
            log.warn("Failed to save reference character '{}': {}", docId, e.getMessage());
        }
    }

    public Optional<ReferenceCharacterDto> find(String systemId, String actorType) {
        String docId = docId(systemId, actorType);
        try {
            GetResponse<Map> response = openSearchClient.get(g -> g
                    .index(INDEX)
                    .id(docId),
                    Map.class);

            if (!response.found() || response.source() == null) {
                return Optional.empty();
            }

            ReferenceCharacterDto ref = objectMapper.convertValue(response.source(), ReferenceCharacterDto.class);
            return Optional.of(ref);

        } catch (Exception e) {
            log.debug("Reference character '{}/{}' not found: {}", systemId, actorType, e.getMessage());
            return Optional.empty();
        }
    }

    public void delete(String systemId, String actorType) {
        String docId = docId(systemId, actorType);
        try {
            openSearchClient.delete(d -> d.index(INDEX).id(docId));
            log.debug("Deleted reference character '{}'", docId);
        } catch (Exception e) {
            log.warn("Failed to delete reference character '{}': {}", docId, e.getMessage());
        }
    }

    private static String docId(String systemId, String actorType) {
        return systemId + ":" + actorType;
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
