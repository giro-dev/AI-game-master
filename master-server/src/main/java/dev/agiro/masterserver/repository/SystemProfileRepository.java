package dev.agiro.masterserver.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.dto.SystemProfileDto;
import dev.agiro.masterserver.entity.SystemProfileEntity;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists System Knowledge Profiles in PostgreSQL with an in-memory
 * read-through cache. This lets the AI Knowledge profile shown in the
 * Foundry System tab survive server restarts and be loaded immediately
 * on first use.
 */
@Slf4j
@Repository
public class SystemProfileRepository {

    private final SystemProfileJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;

    /** Hot cache so reads rarely hit the database during generation. */
    private final Map<String, SystemProfileDto> cache = new ConcurrentHashMap<>();

    public SystemProfileRepository(SystemProfileJpaRepository jpaRepository, ObjectMapper objectMapper) {
        this.jpaRepository = jpaRepository;
        this.objectMapper = objectMapper;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @PostConstruct
    void init() {
        warmCache();
    }

    // ── Public API ───────────────────────────────────────────────────────

    public void save(SystemProfileDto profile) {
        if (profile == null || profile.getSystemId() == null) {
            return;
        }
        String id = profile.getSystemId();
        try {
            SystemProfileEntity entity = toEntity(profile);
            jpaRepository.save(entity);
            cache.put(id, profile);
            log.info("Saved system profile '{}' v{} to PostgreSQL", profile.getSystemId(), profile.getSystemVersion());
        } catch (Exception e) {
            log.error("Failed to save system profile '{}' to PostgreSQL: {}", id, e.getMessage(), e);
            // Still keep in cache so the current session works
            cache.put(id, profile);
        }
    }

    public Optional<SystemProfileDto> find(String systemId) {
        if (systemId == null || systemId.isBlank()) return Optional.empty();

        // Cache hit
        SystemProfileDto cached = cache.get(systemId);
        if (cached != null) return Optional.of(cached);

        // Cache miss → try PostgreSQL
        try {
            Optional<SystemProfileEntity> entity = jpaRepository.findById(systemId);
            if (entity.isPresent()) {
                SystemProfileDto dto = toDto(entity.get());
                cache.put(systemId, dto);
                return Optional.of(dto);
            }
        } catch (Exception e) {
            log.warn("PostgreSQL lookup failed for system profile '{}': {}", systemId, e.getMessage());
        }
        return Optional.empty();
    }

    public void delete(String systemId) {
        if (systemId == null || systemId.isBlank()) return;
        cache.remove(systemId);
        try {
            jpaRepository.deleteById(systemId);
            log.info("Deleted system profile from PostgreSQL: {}", systemId);
        } catch (Exception e) {
            log.warn("Failed to delete system profile '{}' from PostgreSQL: {}", systemId, e.getMessage());
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    /** On startup, load all system profiles into the in-memory cache. */
    private void warmCache() {
        try {
            var entities = jpaRepository.findAll();
            int loaded = 0;
            for (var entity : entities) {
                try {
                    SystemProfileDto dto = toDto(entity);
                    cache.put(entity.getSystemId(), dto);
                    loaded++;
                } catch (Exception e) {
                    log.warn("Failed to deserialize profile '{}': {}", entity.getSystemId(), e.getMessage());
                }
            }
            log.info("Warmed system-profile cache: {} entries from PostgreSQL", loaded);
        } catch (Exception e) {
            log.warn("Could not warm system-profile cache from PostgreSQL: {}", e.getMessage());
        }
    }

    private SystemProfileEntity toEntity(SystemProfileDto dto) {
        try {
            return SystemProfileEntity.builder()
                    .systemId(dto.getSystemId())
                    .systemVersion(dto.getSystemVersion())
                    .systemTitle(dto.getSystemTitle())
                    .lastUpdated(dto.getLastUpdated())
                    .enrichedFromManuals(dto.isEnrichedFromManuals())
                    .profileJson(objectMapper.writeValueAsString(dto))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize SystemProfileDto", e);
        }
    }

    private SystemProfileDto toDto(SystemProfileEntity entity) {
        try {
            return objectMapper.readValue(entity.getProfileJson(), SystemProfileDto.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize SystemProfileDto", e);
        }
    }
}
