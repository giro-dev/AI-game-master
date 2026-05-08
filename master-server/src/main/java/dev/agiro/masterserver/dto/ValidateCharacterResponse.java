package dev.agiro.masterserver.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Validation result returned by the preview/edit validation endpoint.
 */
@Data
public class ValidateCharacterResponse {
    private boolean valid;
    private List<ValidationError> errors = new ArrayList<>();

    @Data
    public static class ValidationError {
        private String field;
        private String message;
        private String severity; // "error", "warning", "info"

        public ValidationError() {}

        public ValidationError(String field, String message, String severity) {
            this.field = field;
            this.message = message;
            this.severity = severity;
        }
    }
}
