package dev.agiro.masterserver.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Response containing multiple generated characters from a batch request.
 */
@Data
public class BatchCharacterResponse {
    private boolean success;
    private String reasoning;
    private int requested;
    private int generated;
    private List<CreateCharacterResponse> characters = new ArrayList<>();
    private List<String> errors = new ArrayList<>();
}
