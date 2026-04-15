package dev.agiro.masterserver.service;

import dev.agiro.masterserver.dto.CorrectionAckDto;
import dev.agiro.masterserver.dto.CorrectionDto;
import dev.agiro.masterserver.dto.SemanticMapDto;
import dev.agiro.masterserver.dto.SystemProfileDto;
import dev.agiro.masterserver.tool.CorrectionRepository;
import dev.agiro.masterserver.tool.SemanticFieldInferenceService;
import dev.agiro.masterserver.tool.SystemProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Feedback / correction loop.
 *
 * <p>When the GM edits an AI-generated actor the Foundry module POSTs the diff here.
 * This service:
 * <ol>
 *   <li>Stores the correction in OpenSearch for audit / future training.</li>
 *   <li>Re-scores the confidence of every {@link SemanticMapDto.FieldMapping} whose
 *       path appears in the edited-fields list.</li>
 *   <li>Recomputes the overall {@link SystemProfileDto#getConfidence()} score.</li>
 *   <li>If confidence drops below {@value #RE_EXTRACTION_THRESHOLD}, invalidates the
 *       cache and marks the profile so the next snapshot triggers a full re-extraction.</li>
 * </ol>
 */
@Slf4j
@Service
public class FeedbackService {

    /**
     * Per-field confidence penalty applied each time a GM edits that field.
     * Three edits on the same field bring confidence from 1.0 → ~0.49.
     */
    static final double CORRECTION_PENALTY = 0.15;

    /**
     * Overall confidence threshold below which a full re-extraction is scheduled.
     */
    static final double RE_EXTRACTION_THRESHOLD = 0.45;

    private final CorrectionRepository correctionRepository;
    private final SystemProfileService systemProfileService;
    private final SemanticFieldInferenceService semanticFieldInferenceService;

    public FeedbackService(CorrectionRepository correctionRepository,
                           SystemProfileService systemProfileService,
                           SemanticFieldInferenceService semanticFieldInferenceService) {
        this.correctionRepository = correctionRepository;
        this.systemProfileService = systemProfileService;
        this.semanticFieldInferenceService = semanticFieldInferenceService;
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Main entry-point: ingest a correction, update confidence, maybe trigger re-extraction.
     *
     * @return acknowledgement with new confidence and whether re-extraction was scheduled
     */
    public CorrectionAckDto ingestCorrection(CorrectionDto correction) {
        log.info("[Feedback] Correction received for {} ({} changed paths)",
                correction.getSystemId(),
                correction.getChangedPaths() == null ? 0 : correction.getChangedPaths().size());

        // 1. Persist
        String correctionId = correctionRepository.save(correction);

        // 2. Load current profile
        SystemProfileDto profile = systemProfileService.getProfile(correction.getSystemId())
                .orElse(null);

        if (profile == null) {
            log.warn("[Feedback] No profile found for '{}' — correction stored but not applied",
                    correction.getSystemId());
            return CorrectionAckDto.builder()
                    .correctionId(correctionId)
                    .systemId(correction.getSystemId())
                    .newConfidence(0.0)
                    .reExtractionTriggered(false)
                    .summary("Correction stored; no existing profile to update.")
                    .build();
        }

        // 3. Re-score affected FieldMappings
        List<String> changedPaths = correction.getChangedPaths();
        if (changedPaths != null && !changedPaths.isEmpty()) {
            rescoreSemanticMap(profile, Set.copyOf(changedPaths));
        }

        // 4. Recompute overall confidence
        int totalFields = countTotalFields(profile);
        double newConfidence = semanticFieldInferenceService
                .computeOverallConfidence(profile.getSemanticMap(), totalFields);
        profile.setConfidence(newConfidence);
        profile.setLastUpdated(Instant.now().toEpochMilli());

        // 5. Persist updated profile
        systemProfileService.saveUpdatedProfile(profile);

        // 6. Trigger re-extraction if confidence is too low
        boolean reExtraction = false;
        if (newConfidence < RE_EXTRACTION_THRESHOLD) {
            log.info("[Feedback] Confidence {:.2f} < threshold {:.2f} for '{}' — scheduling re-extraction",
                    newConfidence, RE_EXTRACTION_THRESHOLD, correction.getSystemId());
            systemProfileService.scheduleReExtraction(correction.getSystemId());
            reExtraction = true;
        }

        String summary = buildSummary(changedPaths, newConfidence, reExtraction);
        log.info("[Feedback] {} — newConfidence={:.2f}, reExtraction={}",
                correction.getSystemId(), newConfidence, reExtraction);

        return CorrectionAckDto.builder()
                .correctionId(correctionId)
                .systemId(correction.getSystemId())
                .newConfidence(newConfidence)
                .reExtractionTriggered(reExtraction)
                .summary(summary)
                .build();
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Lower the confidence of every {@link SemanticMapDto.FieldMapping} whose {@code path}
     * appears in {@code changedPaths}.
     */
    private void rescoreSemanticMap(SystemProfileDto profile, Set<String> changedPaths) {
        SemanticMapDto map = profile.getSemanticMap();
        if (map == null) return;

        penalise(map.getHealth(), changedPaths);
        penalise(map.getHealthSecondary(), changedPaths);
        penalise(map.getLevel(), changedPaths);
        penalise(map.getExperience(), changedPaths);
        penalise(map.getArmorClass(), changedPaths);
        penalise(map.getInitiative(), changedPaths);
        penalise(map.getMovementSpeed(), changedPaths);
        penalise(map.getRollAttribute(), changedPaths);

        if (map.getPrimaryStats() != null) {
            map.getPrimaryStats().forEach(fm -> penalise(fm, changedPaths));
        }
        if (map.getSkills() != null) {
            map.getSkills().forEach(fm -> penalise(fm, changedPaths));
        }
        if (map.getCurrency() != null) {
            map.getCurrency().forEach(fm -> penalise(fm, changedPaths));
        }
    }

    /**
     * Apply the confidence penalty to a single {@link SemanticMapDto.FieldMapping}
     * if its path (or a prefix of a changed path) matches.
     */
    private void penalise(SemanticMapDto.FieldMapping fm, Set<String> changedPaths) {
        if (fm == null) return;
        boolean affected = changedPaths.stream()
                .anyMatch(p -> p.equals(fm.getPath()) || p.startsWith(fm.getPath() + "."));
        if (affected) {
            double updated = Math.max(0.0, fm.getConfidence() - CORRECTION_PENALTY);
            fm.setConfidence(updated);
            log.debug("[Feedback] Penalised '{}' ({}) → confidence={:.2f}",
                    fm.getPath(), fm.getInferredAs(), updated);
        }
    }

    /** Rough estimate of total meaningful fields in the profile for confidence computation. */
    private int countTotalFields(SystemProfileDto profile) {
        if (profile.getFieldGroups() == null) return 20; // safe default
        return profile.getFieldGroups().stream()
                .mapToInt(g -> g.getFieldPaths() == null ? 0 : g.getFieldPaths().size())
                .sum();
    }

    private String buildSummary(List<String> changedPaths, double newConfidence, boolean reExtraction) {
        int count = changedPaths == null ? 0 : changedPaths.size();
        StringBuilder sb = new StringBuilder();
        sb.append(count).append(" field(s) corrected; new confidence=")
                .append(String.format("%.0f%%", newConfidence * 100));
        if (reExtraction) sb.append("; re-extraction scheduled");
        return sb.toString();
    }
}
