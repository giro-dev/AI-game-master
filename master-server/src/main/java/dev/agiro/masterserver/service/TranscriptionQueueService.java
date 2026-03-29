package dev.agiro.masterserver.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Map;
import java.io.IOException;

@Service
public class TranscriptionQueueService {

    private final BlockingQueue<AudioFragment> audioQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final TranscriptionService transcriptionService;

    public TranscriptionQueueService(TranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
        startProcessingQueue();
    }

    public void enqueueAudioFragment(AudioFragment fragment) {
        audioQueue.offer(fragment);
    }

    private void startProcessingQueue() {
        executorService.submit(() -> {
            while (true) {
                try {
                    AudioFragment fragment = audioQueue.take();
                    processAudioFragment(fragment);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private void processAudioFragment(AudioFragment fragment) {
        try {
            String transcription = transcriptionService.transcribeAudio(fragment.getAudioData());
            transcriptionService.saveTranscription(
                fragment.getSessionId(),
                transcription,
                Map.of(
                    "participantId", fragment.getParticipantId(),
                    "timestamp", fragment.getTimestamp()
                )
            );
        } catch (IOException e) {
            e.printStackTrace(); // Log error
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
