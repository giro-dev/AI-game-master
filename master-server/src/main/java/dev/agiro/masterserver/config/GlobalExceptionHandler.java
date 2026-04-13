package dev.agiro.masterserver.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * Catches {@link MaxUploadSizeExceededException} so the client receives a
 * readable JSON error instead of a raw 500 (which browsers may mask as a CORS failure).
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Upload rejected – file too large: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "error", "File too large",
                "message", "The uploaded file exceeds the maximum allowed size (200 MB).",
                "maxFileSize", "200MB"
        ));
    }
}

