package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TranscriptionResult {
    private String text;
    private String language;
    private double duration;
    private List<TranscriptionSegment> segments;
}
