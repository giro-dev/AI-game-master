package dev.agiro.masterserver.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Serves synthesized audio clips stored by {@link dev.agiro.masterserver.service.AudioStoreService}.
 *
 * <p>Example: {@code GET /audio/3f2a1b4c-….wav}
 */
@RestController
@RequestMapping("/audio")
@CrossOrigin(origins = "*")
public class AudioController {

    private final Path storeDir;

    public AudioController(@Value("${audio.store.dir:${java.io.tmpdir}/ai-gm-audio}") String storeDir) {
        this.storeDir = Paths.get(storeDir);
    }

    @GetMapping("/{filename:.+\\.wav}")
    public ResponseEntity<Resource> getAudio(@PathVariable String filename) {
        // Prevent path-traversal attacks
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }
        Path file = storeDir.resolve(filename);
        Resource resource = new FileSystemResource(file);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .contentType(MediaType.parseMediaType("audio/wav"))
                .body(resource);
    }
}

