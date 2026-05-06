package dev.agiro.masterserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agiro.masterserver.config.PiperTtsConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Talks to the {@code wyoming-piper} TCP service to synthesize speech.
 * Active when {@code tts.provider=piper} (default).
 *
 * <p>Wyoming protocol is line-delimited JSON: each event line is a JSON object
 * with optional {@code data_length} / {@code payload_length} fields. When
 * present, the indicated number of bytes follow the JSON line on the wire
 * (data first, then payload). For Piper synthesis we send a {@code synthesize}
 * event and read back {@code audio-start}, one or more {@code audio-chunk}
 * events whose payload is raw 16-bit PCM, and finally {@code audio-stop}.
 *
 * <p>{@link #synthesize} returns a self-contained WAV (header + PCM body) so
 * the consumer (Foundry client) can play it directly.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "tts.provider", havingValue = "piper", matchIfMissing = true)
public class TtsService implements SpeechSynthesisService {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    /** WAV/PCM bit depth Piper produces. */
    private static final int BITS_PER_SAMPLE = 16;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PiperTtsConfig config;
    private final Map<String, String> voiceMap = new HashMap<>();

    public TtsService(PiperTtsConfig config) {
        this.config = config;
    }

    @PostConstruct
    void init() {
        if (config.getVoices() != null) {
            voiceMap.putAll(config.getVoices());
        }
        log.info("TtsService configured: piper at {}:{} ({} voices, default '{}')",
                config.getHost(), config.getPort(), voiceMap.size(), config.getDefaultVoice());
    }

    /**
     * Synthesize {@code text} with the requested voice and return WAV bytes.
     *
     * @param voiceId logical id (e.g. {@code "narrator"} or a Piper model id directly).
     *                Falls back to the configured default voice if unknown.
     * @param pitch   ignored for now (Piper does not expose pitch over Wyoming);
     *                kept for API symmetry with future engines.
     * @param speed   {@code <0> ≈ 1.0}. Wyoming forwards a {@code length_scale} hint
     *                in its synthesize event when supplied; here we leave it to Piper's default.
     * @return WAV bytes, or an empty array on failure.
     */
    public byte[] synthesize(String text, String voiceId, float pitch, float speed) {
        if (text == null || text.isBlank()) return new byte[0];

        String resolvedVoice = resolveVoice(voiceId);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(config.getHost(), config.getPort()), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            InputStream in = socket.getInputStream();

            sendEvent(out, "synthesize", buildSynthesizePayload(text, resolvedVoice), null, null);
            return readAudioToWav(in);
        } catch (IOException e) {
            log.error("Piper TTS synthesis failed for voice '{}': {}", resolvedVoice, e.getMessage());
            return new byte[0];
        }
    }

    private String resolveVoice(String voiceId) {
        if (voiceId == null || voiceId.isBlank()) return config.getDefaultVoice();
        String mapped = voiceMap.get(voiceId);
        return mapped != null ? mapped : voiceId;
    }

    private Map<String, Object> buildSynthesizePayload(String text, String voice) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", text);
        Map<String, Object> voiceObj = new LinkedHashMap<>();
        voiceObj.put("name", voice);
        data.put("voice", voiceObj);
        return data;
    }

    /**
     * Write a Wyoming event to the socket: {@code <json-line>\n[data][payload]}.
     */
    private void sendEvent(DataOutputStream out, String type, Map<String, Object> data, byte[] dataBytes, byte[] payloadBytes) throws IOException {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", type);
        if (data != null) header.put("data", data);
        header.put("data_length", dataBytes == null ? 0 : dataBytes.length);
        header.put("payload_length", payloadBytes == null ? 0 : payloadBytes.length);

        String jsonLine;
        try {
            jsonLine = objectMapper.writeValueAsString(header) + "\n";
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to encode Wyoming event", e);
        }
        out.write(jsonLine.getBytes(StandardCharsets.UTF_8));
        if (dataBytes != null) out.write(dataBytes);
        if (payloadBytes != null) out.write(payloadBytes);
        out.flush();
    }

    /**
     * Drain audio events from {@code in} until {@code audio-stop}, returning a WAV.
     */
    private byte[] readAudioToWav(InputStream in) throws IOException {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        int sampleRate = 22_050; // Piper default
        int channels = 1;

        while (true) {
            String headerLine = readLine(in);
            if (headerLine == null) break;
            JsonNode header = objectMapper.readTree(headerLine);
            String type = header.path("type").asText("");
            int dataLen = header.path("data_length").asInt(0);
            int payloadLen = header.path("payload_length").asInt(0);

            byte[] dataBytes = dataLen > 0 ? readN(in, dataLen) : null;
            byte[] payloadBytes = payloadLen > 0 ? readN(in, payloadLen) : null;

            JsonNode data = dataBytes != null
                    ? objectMapper.readTree(dataBytes)
                    : header.path("data");

            switch (type) {
                case "audio-start" -> {
                    if (data != null) {
                        sampleRate = data.path("rate").asInt(sampleRate);
                        channels = data.path("channels").asInt(channels);
                    }
                }
                case "audio-chunk" -> {
                    if (data != null) {
                        sampleRate = data.path("rate").asInt(sampleRate);
                        channels = data.path("channels").asInt(channels);
                    }
                    if (payloadBytes != null) pcm.write(payloadBytes);
                }
                case "audio-stop" -> {
                    return pcmToWav(pcm.toByteArray(), sampleRate, channels);
                }
                case "error" -> {
                    String msg = data != null ? data.path("text").asText("unknown") : "unknown";
                    throw new IOException("Piper returned error: " + msg);
                }
                default -> log.debug("Ignoring Wyoming event '{}'", type);
            }
        }
        return pcmToWav(pcm.toByteArray(), sampleRate, channels);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return buf.toString(StandardCharsets.UTF_8);
            }
            buf.write(b);
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.UTF_8);
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] out = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(out, read, n - read);
            if (r == -1) throw new IOException("Unexpected end of stream while reading " + n + " bytes");
            read += r;
        }
        return out;
    }

    /** Wrap raw PCM bytes in a 44-byte WAV header. */
    static byte[] pcmToWav(byte[] pcm, int sampleRate, int channels) {
        int byteRate = sampleRate * channels * BITS_PER_SAMPLE / 8;
        int blockAlign = channels * BITS_PER_SAMPLE / 8;
        int dataSize = pcm.length;
        int chunkSize = 36 + dataSize;

        ByteBuffer buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(chunkSize);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);              // fmt sub-chunk size
        buf.putShort((short) 1);     // PCM
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(byteRate);
        buf.putShort((short) blockAlign);
        buf.putShort((short) BITS_PER_SAMPLE);
        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(dataSize);
        buf.put(pcm);
        return buf.array();
    }
}
