package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response returned after a correction is ingested.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CorrectionAckDto {

    /** OpenSearch document ID for the stored correction */
    private String correctionId;

    /** System ID the correction applies to */
    private String systemId;

    /** Updated overall confidence score for the system profile (0.0–1.0) */
    private double newConfidence;

    /** True if confidence dropped below the threshold and re-extraction was scheduled */
    private boolean reExtractionTriggered;

    /** Human-readable summary of what changed */
    private String summary;
}
