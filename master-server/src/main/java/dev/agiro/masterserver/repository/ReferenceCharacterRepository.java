package dev.agiro.masterserver.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.ReferenceCharacterDto;
import dev.agiro.masterserver.entity.ReferenceCharacterEntity;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists reference characters in PostgreSQL with an in-memory read-through cache.
 * <p>
 * Table: {@code reference_characters}
 * Primary key: {@code {systemId}:{actorType}}
 */
@Slf4j
@Repository
public class ReferenceCharacterRepository {

    private final ReferenceCharacterJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;

    /** Hot cache so reads never hit the database during generation. */
    private final Map<String, ReferenceCharacterDto> cache = new ConcurrentHashMap<>();

    public ReferenceCharacterRepository(ReferenceCharacterJpaRepository jpaRepository, ObjectMapper objectMapper) {
        this.jpaRepository = jpaRepository;
        this.objectMapper = objectMapper;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @PostConstruct
    void init() {
        warmCache();
    }

    // ── Public API ───────────────────────────────────────────────────────

    public void save(ReferenceCharacterDto ref) {
        String id = docId(ref.getSystemId(), ref.getActorType());
        try {
            ReferenceCharacterEntity entity = toEntity(ref);
            jpaRepository.save(entity);
            cache.put(id, ref);
            log.info("Saved reference character '{}' → {} to PostgreSQL", ref.getLabel(), id);
        } catch (Exception e) {
            log.error("Failed to save reference character '{}' to PostgreSQL: {}", id, e.getMessage(), e);
            // Still keep in cache so the current session works
            cache.put(id, ref);
        }
    }

    public Optional<ReferenceCharacterDto> find(String systemId, String actorType) {
        String id = docId(systemId, actorType);

        // Cache hit
        ReferenceCharacterDto cached = cache.get(id);
        if (cached != null) return Optional.of(cached);

        // Cache miss → try PostgreSQL
        try {
            Optional<ReferenceCharacterEntity> entity = jpaRepository.findById(id);
            if (entity.isPresent()) {
                ReferenceCharacterDto dto = toDto(entity.get());
                cache.put(id, dto);
                return Optional.of(dto);
            }
        } catch (Exception e) {
            log.warn("PostgreSQL lookup failed for reference character '{}': {}", id, e.getMessage());
        }
        return Optional.empty();
    }

    public void delete(String systemId, String actorType) {
        String id = docId(systemId, actorType);
        cache.remove(id);
        try {
            jpaRepository.deleteById(id);
            log.info("Deleted reference character from PostgreSQL: {}", id);
        } catch (Exception e) {
            log.warn("Failed to delete reference character '{}' from PostgreSQL: {}", id, e.getMessage());
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private static String docId(String systemId, String actorType) {
        return systemId + ":" + actorType;
    }

    /** On startup, load all reference characters into the in-memory cache. */
    private void warmCache() {
        try {
            var entities = jpaRepository.findAll();
            int loaded = 0;
            for (var entity : entities) {
                try {
                    ReferenceCharacterDto dto = toDto(entity);
                    cache.put(entity.getId(), dto);
                    loaded++;
                } catch (Exception e) {
                    log.warn("Failed to deserialize reference character '{}': {}", entity.getId(), e.getMessage());
                }
            }
            log.info("Warmed reference-character cache: {} entries from PostgreSQL", loaded);
        } catch (Exception e) {
            log.warn("Could not warm reference-character cache from PostgreSQL: {}", e.getMessage());
        }
    }

    private ReferenceCharacterEntity toEntity(ReferenceCharacterDto dto) {
        try {
            return ReferenceCharacterEntity.builder()
                    .id(docId(dto.getSystemId(), dto.getActorType()))
                    .systemId(dto.getSystemId())
                    .actorType(dto.getActorType())
                    .label(dto.getLabel())
                    .capturedAt(dto.getCapturedAt())
                    .actorDataJson(objectMapper.writeValueAsString(dto.getActorData()))
                    .itemsJson(objectMapper.writeValueAsString(dto.getItems()))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ReferenceCharacterDto", e);
        }
    }

    private ReferenceCharacterDto toDto(ReferenceCharacterEntity entity) {
        try {
            Map<String, Object> actorData = objectMapper.readValue(
                    entity.getActorDataJson(), new TypeReference<>() {});
            List<Map<String, Object>> items = objectMapper.readValue(
                    entity.getItemsJson(), new TypeReference<>() {});
            return ReferenceCharacterDto.builder()
                    .systemId(entity.getSystemId())
                    .actorType(entity.getActorType())
                    .label(entity.getLabel())
                    .capturedAt(entity.getCapturedAt())
                    .actorData(actorData)
                    .items(items)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize ReferenceCharacterDto", e);
        }
    }
}

