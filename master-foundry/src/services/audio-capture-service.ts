/**
 * Captures the player's microphone, encodes the recording as base64, and ships
 * it over the existing WebSocket to the AI Director. Designed to be a simple
 * push-to-talk toggle: {@link start} / {@link stop} delimit one utterance.
 */

import type { WebSocketClient } from '../websocket-client.js';

export interface AudioCaptureOptions {
    adventureSessionId: string;
    worldId?: string;
    foundrySystem?: string;
    playerName?: string;
    worldState?: any;
    /** Called whenever the recording state changes (true = recording). */
    onStateChange?: (recording: boolean) => void;
    /** Called when an utterance has been sent for transcription. */
    onSubmitting?: () => void;
}

export class AudioCaptureService {
    private mediaRecorder: MediaRecorder | null = null;
    private chunks: Blob[] = [];
    private stream: MediaStream | null = null;
    private recording = false;

    constructor(private readonly ws: WebSocketClient, private readonly opts: AudioCaptureOptions) { }

    isRecording(): boolean {
        return this.recording;
    }

    async toggle(): Promise<void> {
        if (this.recording) {
            await this.stop();
        } else {
            await this.start();
        }
    }

    async start(): Promise<void> {
        if (this.recording) return;

        if (!navigator.mediaDevices?.getUserMedia) {
            throw new Error('Microphone access is not available in this browser');
        }

        this.stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        const mime = this.pickMime();
        this.mediaRecorder = new MediaRecorder(this.stream, mime ? { mimeType: mime } : undefined);
        this.chunks = [];

        this.mediaRecorder.ondataavailable = (e: BlobEvent) => {
            if (e.data && e.data.size > 0) this.chunks.push(e.data);
        };

        this.mediaRecorder.onstop = () => {
            const blob = new Blob(this.chunks, { type: this.mediaRecorder?.mimeType || 'audio/webm' });
            this.chunks = [];
            this.releaseStream();
            this.recording = false;
            this.opts.onStateChange?.(false);
            this.submit(blob).catch(err => console.error('[AudioCapture] submit failed', err));
        };

        this.mediaRecorder.start();
        this.recording = true;
        this.opts.onStateChange?.(true);
    }

    async stop(): Promise<void> {
        if (!this.recording || !this.mediaRecorder) return;
        // The actual blob handling happens in the onstop callback.
        this.mediaRecorder.stop();
    }

    cancel(): void {
        if (this.mediaRecorder && this.recording) {
            this.mediaRecorder.onstop = null;
            try { this.mediaRecorder.stop(); } catch (_e) { /* noop */ }
        }
        this.releaseStream();
        this.chunks = [];
        this.recording = false;
        this.opts.onStateChange?.(false);
    }

    private async submit(blob: Blob): Promise<void> {
        this.opts.onSubmitting?.();
        const audioBase64 = await this.blobToBase64(blob);
        this.ws.sendTranscription({
            audioBase64,
            adventureSessionId: this.opts.adventureSessionId,
            worldId: this.opts.worldId,
            foundrySystem: this.opts.foundrySystem,
            playerName: this.opts.playerName,
            worldState: this.opts.worldState
        });
    }

    private blobToBase64(blob: Blob): Promise<string> {
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onloadend = () => {
                const result = reader.result as string;
                const idx = result.indexOf(',');
                resolve(idx >= 0 ? result.substring(idx + 1) : result);
            };
            reader.onerror = () => reject(reader.error);
            reader.readAsDataURL(blob);
        });
    }

    private pickMime(): string | undefined {
        const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/wav'];
        for (const m of candidates) {
            if ((window as any).MediaRecorder?.isTypeSupported?.(m)) return m;
        }
        return undefined;
    }

    private releaseStream(): void {
        if (this.stream) {
            this.stream.getTracks().forEach(t => t.stop());
            this.stream = null;
        }
        this.mediaRecorder = null;
    }
}
