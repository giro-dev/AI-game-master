package dev.agiro.masterserver.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Stores synthesized WAV audio files on disk and returns a URL the client can
 * use to fetch them over plain HTTP — avoiding the overhead of embedding large
 * base64 blobs inside WebSocket/JSON responses.
 *
 * <p>Files are kept for {@code audio.store.ttl-minutes} minutes (default 60)
 * and then cleaned up by a background scheduler.
 */
@Slf4j
@Service
public class AudioStoreService {

    @Value("${audio.store.dir:${java.io.tmpdir}/ai-gm-audio}")
    private String storeDir;

    /** Public base-URL prefix – must match the path registered in AudioController. */
    @Value("${audio.store.base-url:/audio}")
    private String baseUrl;

    @Value("${audio.store.ttl-minutes:60}")
    private int ttlMinutes;

    private Path storePath;

    @PostConstruct
    public void init() throws IOException {
        storePath = Paths.get(storeDir);
        Files.createDirectories(storePath);
        log.info("AudioStoreService: serving audio from {} at {}", storePath, baseUrl);
    }

    /**
     * Persists {@code wav} bytes and returns a URL path such as
     * {@code /audio/3f2a1b4c-….wav} that can be fetched by the client.
     */
    public String store(byte[] wav) {
        String filename = UUID.randomUUID() + ".wav";
        try {
            Files.write(storePath.resolve(filename), wav);
        } catch (IOException e) {
            log.error("Failed to store audio file {}", filename, e);
            return null;
        }
        return baseUrl + "/" + filename;
    }

    /** Deletes audio files older than the configured TTL. Runs every 10 minutes. */
    @Scheduled(fixedDelayString = "PT10M")
    public void cleanup() {
        Instant cutoff = Instant.now().minus(ttlMinutes, ChronoUnit.MINUTES);
        try (var stream = Files.list(storePath)) {
            stream.filter(p -> p.toString().endsWith(".wav"))
                    .filter(p -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                            return attrs.creationTime().toInstant().isBefore(cutoff);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("Could not delete stale audio file {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Audio cleanup failed", e);
        }
    }
}

