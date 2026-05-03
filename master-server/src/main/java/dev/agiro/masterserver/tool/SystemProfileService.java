package dev.agiro.masterserver.tool;

import dev.agiro.masterserver.dto.ReferenceCharacterDto;
import dev.agiro.masterserver.dto.SystemProfileDto;
import dev.agiro.masterserver.dto.SystemSnapshotDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System Knowledge Profile CRUD + orchestration.
 * Delegates heavy analysis to {@link SystemProfileAnalyzer}.
 */
@Slf4j
@Service
public class SystemProfileService {

    private final SystemProfileRepository profileRepository;
    private final ReferenceCharacterRepository referenceCharacterRepository;
    private final SystemProfileAnalyzer analyzer;
    private final Map<String, SystemProfileDto> cache = new ConcurrentHashMap<>();

    public SystemProfileService(SystemProfileRepository profileRepository,
                                ReferenceCharacterRepository referenceCharacterRepository,
                                SystemProfileAnalyzer analyzer) {
        this.profileRepository = profileRepository;
        this.referenceCharacterRepository = referenceCharacterRepository;
        this.analyzer = analyzer;
    }

    // ── Profile lifecycle ───────────────────────────────────────────────

    /**
     * Process a system snapshot: build or return cached profile.
     */
    public SystemProfileDto processSnapshot(SystemSnapshotDto snapshot) {
        log.info("Processing snapshot for: {} v{}", snapshot.getSystemId(), snapshot.getSystemVersion());

        try {
            SystemProfileDto existing = getProfile(snapshot.getSystemId()).orElse(null);
            if (existing != null && snapshot.getSystemVersion().equals(existing.getSystemVersion())) {
                log.info("Profile up to date for {} v{}", snapshot.getSystemId(), snapshot.getSystemVersion());
                return existing;
            }

            // Delegate all heavy analysis
            SystemProfileDto profile = analyzer.analyze(snapshot);

            // Try manual enrichment (non-blocking)
            try { analyzer.enrichFromManuals(profile, snapshot.getSystemId()); }
            catch (Exception e) { log.warn("Manual enrichment failed: {}", e.getMessage()); }

            // Persist
            cache.put(snapshot.getSystemId(), profile);
            profileRepository.save(profile);
            return profile;

        } catch (Exception e) {
            log.error("Snapshot processing failed for {}", snapshot.getSystemId(), e);
            return analyzer.buildFallbackProfile(snapshot);
        }
    }

    /**
     * Get profile by system ID (cache → DB).
     */
    public Optional<SystemProfileDto> getProfile(String systemId) {
        SystemProfileDto cached = cache.get(systemId);
        if (cached != null) return Optional.of(cached);

        Optional<SystemProfileDto> stored = profileRepository.find(systemId);
        stored.ifPresent(p -> cache.put(systemId, p));
        return stored;
    }

    /**
     * Re-enrich from ingested manuals.
     */
    public void enrichFromIngestion(String systemId, List<Document> classifiedChunks, List<Document> entityDocs) {
        SystemProfileDto profile = getProfile(systemId).orElse(null);
        if (profile == null) { log.warn("No profile for {} to enrich", systemId); return; }

        try {
            analyzer.enrichFromManuals(profile, systemId);
            profile.setEnrichedFromManuals(true);
            profile.setLastUpdated(Instant.now().toEpochMilli());
            cache.put(systemId, profile);
            profileRepository.save(profile);
            log.info("Profile enriched for {}", systemId);
        } catch (Exception e) {
            log.warn("Enrichment failed: {}", e.getMessage());
        }
    }

    public void invalidateCache(String systemId) {
        cache.remove(systemId);
    }

    /**
     * Persist an already-modified profile (e.g. after confidence re-scoring).
     * Updates the in-memory cache and writes through to OpenSearch.
     */
    public void saveUpdatedProfile(SystemProfileDto profile) {
        cache.put(profile.getSystemId(), profile);
        profileRepository.save(profile);
        log.debug("Saved updated profile for '{}'", profile.getSystemId());
    }

    /**
     * Mark a system profile as needing re-extraction and invalidate its cache entry.
     * The next time a snapshot arrives for this system the profile will be fully rebuilt.
     */
    public void scheduleReExtraction(String systemId) {
        SystemProfileDto profile = getProfile(systemId).orElse(null);
        if (profile != null) {
            // Setting version to empty string forces a rebuild on the next snapshot
            profile.setSystemVersion("");
            profileRepository.save(profile);
            log.info("Scheduled re-extraction for '{}' (version cleared)", systemId);
        }
        cache.remove(systemId);
    }

    // ── Reference Characters ────────────────────────────────────────────

    public void storeReferenceCharacter(ReferenceCharacterDto ref) {
        referenceCharacterRepository.save(ref);
        log.info("Stored reference '{}' for {}:{}", ref.getLabel(), ref.getSystemId(), ref.getActorType());
    }

    public Optional<ReferenceCharacterDto> getReferenceCharacter(String systemId, String actorType) {
        return referenceCharacterRepository.find(systemId, actorType);
    }

    public void deleteReferenceCharacter(String systemId, String actorType) {
        referenceCharacterRepository.delete(systemId, actorType);
        log.info("Deleted reference for {}:{}", systemId, actorType);
    }
}

