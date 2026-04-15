package dev.agiro.masterserver.service;

import dev.agiro.masterserver.dto.SpeakerContextDto;
import dev.agiro.masterserver.dto.TranscriptionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Queue-based transcription processor.
 * Audio fragments are enqueued from the WebSocket controller and processed
 * asynchronously by a single background thread to avoid blocking callers.
 */
@Slf4j
@Service
public class TranscriptionQueueService {

    private final BlockingQueue<AudioFragment> audioQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "transcription-worker");
        t.setDaemon(true);
        return t;
    });
    private final TranscriptionService transcriptionService;

    public TranscriptionQueueService(TranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
        startProcessingQueue();
    }

    /**
     * Enqueue an audio fragment with full speaker context for async transcription.
     */
    public void enqueueAudioFragment(AudioFragment fragment) {
        if (!audioQueue.offer(fragment)) {
            log.warn("[TranscriptionQueue] Queue full, dropping fragment from user '{}'",
                    fragment.getSpeaker() != null ? fragment.getSpeaker().getUserId() : "unknown");
        }
    }

    /**
     * Legacy enqueue — no speaker context.
     */
    public void enqueueAudioFragment(String sessionId, String participantId, byte[] audioData, long timestamp) {
        SpeakerContextDto speaker = SpeakerContextDto.builder()
                .sessionId(sessionId)
                .userId(participantId)
                .avSource("websocket-legacy")
                .build();
        enqueueAudioFragment(new AudioFragment(audioData, "webm", speaker, timestamp));
    }

    private void startProcessingQueue() {
        executorService.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    AudioFragment fragment = audioQueue.take();
                    processAudioFragment(fragment);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("[TranscriptionQueue] Unexpected error processing fragment", e);
                }
            }
        });
    }

    private void processAudioFragment(AudioFragment fragment) {
        try {
            TranscriptionResult result = transcriptionService.transcribe(
                    fragment.getAudioData(),
                    fragment.getAudioFormat(),
                    fragment.getSpeaker());

            if (result.getError() != null) {
                log.warn("[TranscriptionQueue] Transcription error for user '{}': {}",
                        fragment.getSpeaker() != null ? fragment.getSpeaker().getUserId() : "?",
                        result.getError());
            } else {
                log.debug("[TranscriptionQueue] Transcribed '{}' ({} chars) for user '{}'",
                        result.getTranscriptionId(),
                        result.getText() != null ? result.getText().length() : 0,
                        fragment.getSpeaker() != null ? fragment.getSpeaker().getUserId() : "?");
            }
        } catch (Exception e) {
            log.error("[TranscriptionQueue] Failed to process audio fragment", e);
        }
    }

    // ── AudioFragment ─────────────────────────────────────────────────────

    public static class AudioFragment {
        private final byte[] audioData;
        private final String audioFormat;
        private final SpeakerContextDto speaker;
        private final long timestamp;

        public AudioFragment(byte[] audioData, String audioFormat, SpeakerContextDto speaker, long timestamp) {
            this.audioData = audioData;
            this.audioFormat = audioFormat != null ? audioFormat : "webm";
            this.speaker = speaker;
            this.timestamp = timestamp;
        }

        public byte[] getAudioData()      { return audioData; }
        public String getAudioFormat()    { return audioFormat; }
        public SpeakerContextDto getSpeaker() { return speaker; }
        public long getTimestamp()        { return timestamp; }

        // ── Legacy accessors (kept for backward compatibility) ──
        public String getSessionId()     { return speaker != null ? speaker.getSessionId() : null; }
        public String getParticipantId() { return speaker != null ? speaker.getUserId() : null; }
    }
}
