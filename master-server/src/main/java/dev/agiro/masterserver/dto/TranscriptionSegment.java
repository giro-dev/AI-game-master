package dev.agiro.masterserver.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TranscriptionSegment {
    private double start;
    private double end;
    private String text;
}
