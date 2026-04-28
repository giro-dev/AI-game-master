package dev.agiro.masterserver.service;

import dev.agiro.masterserver.dto.TranscriptionResult;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@Slf4j
@Service
public class TranscriptionQueueService {

    private final BlockingQueue<AudioFragment> audioQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final TranscriptionService transcriptionService;
    private volatile BiConsumer<String, TranscriptionResult> onTranscriptionComplete;
    private volatile BiConsumer<String, String> onTranscriptionFailed;

    public TranscriptionQueueService(TranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
        startProcessingQueue();
    }

    public void setOnTranscriptionComplete(BiConsumer<String, TranscriptionResult> callback) {
        this.onTranscriptionComplete = callback;
    }

    public void setOnTranscriptionFailed(BiConsumer<String, String> callback) {
        this.onTranscriptionFailed = callback;
    }

    public void enqueueAudioFragment(AudioFragment fragment) {
        audioQueue.offer(fragment);
        log.debug("Enqueued audio fragment for session={}, queue size={}", fragment.getSessionId(), audioQueue.size());
    }

    private void startProcessingQueue() {
        executorService.submit(() -> {
            log.info("Transcription queue processor started");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    AudioFragment fragment = audioQueue.take();
                    processAudioFragment(fragment);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("Transcription queue processor interrupted, shutting down");
                    break;
                }
            }
        });
    }

    private void processAudioFragment(AudioFragment fragment) {
        String transcriptionId = UUID.randomUUID().toString();
        try {
            log.info("Processing audio fragment: session={}, participant={}, size={} bytes",
                    fragment.getSessionId(), fragment.getParticipantId(), fragment.getAudioData().length);

            TranscriptionResult result = transcriptionService.transcribeAudio(fragment.getAudioData());

            if (result == null || result.getText() == null || result.getText().isBlank()) {
                log.warn("Empty transcription result for session={}", fragment.getSessionId());
                notifyFailed(fragment.getSessionId(), "Empty transcription result");
                return;
            }

            transcriptionService.saveTranscription(
                    transcriptionId,
                    result,
                    Map.of(
                            "sessionId", fragment.getSessionId(),
                            "participantId", fragment.getParticipantId(),
                            "audioTimestamp", fragment.getTimestamp()
                    )
            );

            notifyComplete(fragment.getSessionId(), result);

        } catch (IOException e) {
            log.error("Failed to save transcription for session={}: {}", fragment.getSessionId(), e.getMessage());
            notifyFailed(fragment.getSessionId(), "Failed to save: " + e.getMessage());
        } catch (Exception e) {
            log.error("Transcription failed for session={}: {}", fragment.getSessionId(), e.getMessage(), e);
            notifyFailed(fragment.getSessionId(), "Transcription error: " + e.getMessage());
        }
    }

    private void notifyComplete(String sessionId, TranscriptionResult result) {
        if (onTranscriptionComplete != null) {
            try {
                onTranscriptionComplete.accept(sessionId, result);
            } catch (Exception e) {
                log.error("Error in transcription complete callback: {}", e.getMessage());
            }
        }
    }

    private void notifyFailed(String sessionId, String error) {
        if (onTranscriptionFailed != null) {
            try {
                onTranscriptionFailed.accept(sessionId, error);
            } catch (Exception e) {
                log.error("Error in transcription failed callback: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down transcription queue service");
        executorService.shutdownNow();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Transcription executor did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static class AudioFragment {
        private final String sessionId;
        private final String participantId;
        private final byte[] audioData;
        private final long timestamp;

        public AudioFragment(String sessionId, String participantId, byte[] audioData, long timestamp) {
            this.sessionId = sessionId;
            this.participantId = participantId;
            this.audioData = audioData;
            this.timestamp = timestamp;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getParticipantId() {
            return participantId;
        }

        public byte[] getAudioData() {
            return audioData;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}
