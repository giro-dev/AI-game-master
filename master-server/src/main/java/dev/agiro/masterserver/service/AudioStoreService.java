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
     * Persists audio bytes and returns a URL path such as
     * {@code /audio/3f2a1b4c-….mp3}. The extension is inferred from the first
     * bytes (MP3 = 0xFF 0xFB / 0xFF 0xF3 / ID3; WAV = RIFF).
     */
    public String store(byte[] audio) {
        String ext = detectExtension(audio);
        String filename = UUID.randomUUID() + "." + ext;
        try {
            Files.write(storePath.resolve(filename), audio);
        } catch (IOException e) {
            log.error("Failed to store audio file {}", filename, e);
            return null;
        }
        return baseUrl + "/" + filename;
    }

    private static String detectExtension(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return "wav";
        // WAV: RIFF header
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') return "wav";
        // MP3: ID3 tag or sync bytes (0xFF 0xFB / 0xFF 0xF3 / 0xFF 0xF2)
        if ((bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') ||
            ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0)) return "mp3";
        return "wav";
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

