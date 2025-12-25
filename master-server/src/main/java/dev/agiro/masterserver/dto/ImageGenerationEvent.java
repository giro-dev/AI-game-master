package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Image generation event payload
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerationEvent {

    /**
     * Unique identifier for this image generation request
     */
    private String requestId;

    /**
     * Prompt used to generate the image
     */
    private String prompt;

    /**
     * Path to the generated image (relative to Foundry's data directory)
     */
    private String imagePath;

    /**
     * Full URL to access the image
     */
    private String imageUrl;

    /**
     * Character or entity this image is associated with
     */
    private String associatedEntity;

    /**
     * Type of image (portrait, token, scene, etc.)
     */
    private String imageType;

    /**
     * Image dimensions
     */
    private Integer width;
    private Integer height;

    /**
     * Additional metadata
     */
    private String metadata;
}

