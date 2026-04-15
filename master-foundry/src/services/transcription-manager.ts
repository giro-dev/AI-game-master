/**
 * TranscriptionManager — agnostic speech-to-text for AI Game Master
 *
 * How it works:
 *
 * 1. Speaking detection (SYSTEM-AGNOSTIC):
 *    A MutationObserver watches Foundry's #camera-views container for the
 *    `.speaking` CSS class that every AV client (LiveKit, Jitsi, built-in)
 *    adds when a participant is vocally active.
 *
 *    Additionally, if the `fvtt-module-avclient-livekit` module is present,
 *    we hook into the `liveKitClientInitialized` Foundry hook to subscribe
 *    to ParticipantEvent.IsSpeakingChanged for richer event data.
 *
 * 2. Audio capture (LOCAL USER ONLY — privacy-sound approach):
 *    When the local user starts speaking, a MediaRecorder is started on their
 *    microphone stream.  When they stop, the blob is sent to the backend with
 *    full SpeakerContext metadata.
 *
 * 3. Speaker metadata:
 *    For any detected user we resolve: userId, userName, isGM, characterId,
 *    characterName, characterType, worldId, sceneName, systemId.
 *
 * 4. Transmission:
 *    Audio is sent to POST /gm/transcription/submit as multipart/form-data
 *    with the audio blob + JSON speaker context.
 *    Transcription results come back synchronously in the HTTP response
 *    and are also pushed via WebSocket (TRANSCRIPTION_COMPLETED event).
 */

import type { SpeakerContext } from '../types/index.js';

/** Minimum recording duration in ms before sending (avoids sending micro-clicks) */
const MIN_RECORDING_MS = 500;

/** Maximum recording duration in ms — stop and flush even if still speaking */
const MAX_RECORDING_MS = 20_000;

/** How long (ms) to wait after `.speaking` is removed before finalising recording */
const STOP_DEBOUNCE_MS = 800;

export class TranscriptionManager {
    private readonly serverUrl: string;
    private readonly sessionId: string;

    private mediaRecorder: MediaRecorder | null = null;
    private recordingChunks: BlobPart[] = [];
    private recordingStartMs: number = 0;
    private stopTimer: ReturnType<typeof setTimeout> | null = null;
    private maxDurationTimer: ReturnType<typeof setTimeout> | null = null;
    private observer: MutationObserver | null = null;

    /** userId of the local user as Foundry knows them */
    private readonly localUserId: string;

    /** Whether we are currently recording */
    private recording = false;

    /** Callbacks registered externally for transcript results */
    private onTranscript: ((result: TranscriptionEvent) => void)[] = [];

    constructor(serverUrl: string, sessionId: string) {
        this.serverUrl = serverUrl;
        this.sessionId = sessionId;
        this.localUserId = game.user?.id ?? '';
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Start the manager: set up speaking-detection observers and request mic access.
     */
    async start(): Promise<void> {
        console.log('[Transcription] Starting manager…');

        const granted = await this._requestMicrophone();
        if (!granted) {
            console.warn('[Transcription] Microphone not available — transcription disabled');
            return;
        }

        this._setupDomObserver();
        this._hookLiveKit();

        console.log('[Transcription] Manager active');
    }

    /** Stop all recording and disconnect observers. */
    stop(): void {
        this._stopRecording(false);
        if (this.observer) {
            this.observer.disconnect();
            this.observer = null;
        }
        if (this.stopTimer) clearTimeout(this.stopTimer);
        if (this.maxDurationTimer) clearTimeout(this.maxDurationTimer);
    }

    /** Register a callback that is called each time a transcript arrives. */
    onTranscribed(cb: (result: TranscriptionEvent) => void): void {
        this.onTranscript.push(cb);
    }

    // ── Speaking detection: DOM MutationObserver (agnostic) ────────────────

    /**
     * Watches for `.speaking` class changes on `.camera-view` elements.
     * This is the agnostic path that works with ANY Foundry AV client.
     */
    private _setupDomObserver(): void {
        const container = document.getElementById('camera-views') ??
                          document.querySelector('.camera-views-wrapper') ??
                          document.body;

        this.observer = new MutationObserver((mutations) => {
            for (const mutation of mutations) {
                if (mutation.type !== 'attributes' || mutation.attributeName !== 'class') continue;
                const el = mutation.target as HTMLElement;
                if (!el.classList.contains('camera-view')) continue;

                const userId = el.dataset['user'];
                if (!userId) continue;

                const isSpeaking = el.classList.contains('speaking');
                this._handleSpeakingChanged(userId, isSpeaking, 'dom-observer');
            }
        });

        this.observer.observe(container, {
            subtree: true,
            attributes: true,
            attributeFilter: ['class'],
        });
    }

    // ── Speaking detection: LiveKit hook (optional enhancement) ────────────

    /**
     * If the LiveKit module is installed, subscribe to its IsSpeakingChanged event
     * for lower-latency detection than the DOM observer provides.
     */
    private _hookLiveKit(): void {
        Hooks.on('liveKitClientInitialized', (liveKitClient: any) => {
            console.log('[Transcription] LiveKit hook available — subscribing to speaking events');
            const room: any = liveKitClient?.liveKitRoom;
            if (!room) return;

            // Listen to all participants including local
            room.on('activeSpeakersChanged', (speakers: any[]) => {
                const speakingIds = new Set(
                    (speakers ?? []).map((p: any) => this._participantToFoundryUserId(p, liveKitClient))
                        .filter(Boolean)
                );
                // Notify stops for those no longer in the list (LiveKit gives us the full current list)
                const allParticipants: string[] = [];
                liveKitClient?.liveKitParticipants?.forEach((_p: any, uid: string) => allParticipants.push(uid));

                for (const uid of allParticipants) {
                    this._handleSpeakingChanged(uid, speakingIds.has(uid), 'livekit');
                }
            });
        });
    }

    private _participantToFoundryUserId(participant: any, liveKitClient: any): string | null {
        try {
            return liveKitClient?.getParticipantFVTTUser?.(participant)?.id ?? null;
        } catch {
            return null;
        }
    }

    // ── Core speaking logic ──────────────────────────────────────────────

    private _handleSpeakingChanged(userId: string, isSpeaking: boolean, source: string): void {
        // Only record the LOCAL user's speech
        if (userId !== this.localUserId) return;

        if (isSpeaking) {
            this._onLocalSpeakingStarted(source);
        } else {
            this._onLocalSpeakingStopped();
        }
    }

    private _onLocalSpeakingStarted(source: string): void {
        if (this.recording) {
            // Already recording — cancel any pending stop timer
            if (this.stopTimer) {
                clearTimeout(this.stopTimer);
                this.stopTimer = null;
            }
            return;
        }

        if (!this._micStream) {
            console.debug('[Transcription] No mic stream, skipping recording');
            return;
        }

        console.debug('[Transcription] Local user started speaking (source=%s)', source);
        this._startRecording(source);
    }

    private _onLocalSpeakingStopped(): void {
        if (!this.recording) return;

        // Debounce: wait a bit before finalising in case speaking resumes
        if (this.stopTimer) clearTimeout(this.stopTimer);
        this.stopTimer = setTimeout(() => {
            this.stopTimer = null;
            this._stopRecording(true);
        }, STOP_DEBOUNCE_MS);
    }

    // ── Audio recording ──────────────────────────────────────────────────

    private _micStream: MediaStream | null = null;

    private async _requestMicrophone(): Promise<boolean> {
        // Try to reuse the AV system's existing stream first (most agnostic)
        try {
            const avStream = this._getExistingAvStream();
            if (avStream) {
                this._micStream = avStream;
                return true;
            }
        } catch {
            // ignore
        }

        // Fall back to requesting our own mic access
        try {
            this._micStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
            return true;
        } catch (e) {
            console.warn('[Transcription] getUserMedia failed:', e);
            return false;
        }
    }

    /** Try to grab the existing audio stream from the AV subsystem (LiveKit or built-in). */
    private _getExistingAvStream(): MediaStream | null {
        // LiveKit: access local audio track
        try {
            const client = (game as any).webrtc?.client;
            const liveKitClient = client?._liveKitClient;
            const audioTrack = liveKitClient?.audioTrack;
            if (audioTrack?.mediaStreamTrack) {
                const stream = new MediaStream([audioTrack.mediaStreamTrack]);
                return stream;
            }
        } catch { /* ignore */ }

        return null;
    }

    private _startRecording(source: string): void {
        if (!this._micStream) return;
        this.recordingChunks = [];
        this.recordingStartMs = Date.now();
        this.recording = true;

        const options = this._bestMimeType();
        try {
            this.mediaRecorder = new MediaRecorder(this._micStream, options);
        } catch {
            // Fallback without mimeType
            this.mediaRecorder = new MediaRecorder(this._micStream);
        }

        this.mediaRecorder.ondataavailable = (e) => {
            if (e.data.size > 0) this.recordingChunks.push(e.data);
        };

        this.mediaRecorder.onstop = () => {
            const duration = Date.now() - this.recordingStartMs;
            if (duration >= MIN_RECORDING_MS && this.recordingChunks.length > 0) {
                const blob = new Blob(this.recordingChunks, { type: this.mediaRecorder?.mimeType ?? 'audio/webm' });
                void this._sendAudio(blob, source);
            }
            this.recordingChunks = [];
            this.recording = false;
        };

        this.mediaRecorder.start(500); // collect in 500ms chunks

        // Safety: never record more than MAX_RECORDING_MS
        this.maxDurationTimer = setTimeout(() => {
            if (this.recording) this._stopRecording(true);
        }, MAX_RECORDING_MS);
    }

    private _stopRecording(send: boolean): void {
        if (this.maxDurationTimer) { clearTimeout(this.maxDurationTimer); this.maxDurationTimer = null; }
        if (!this.mediaRecorder || this.mediaRecorder.state === 'inactive') {
            this.recording = false;
            return;
        }
        if (!send) {
            // Discard
            this.mediaRecorder.ondataavailable = null;
            this.mediaRecorder.onstop = () => { this.recording = false; };
        }
        this.mediaRecorder.stop();
    }

    private _bestMimeType(): { mimeType?: string } {
        const candidates = [
            'audio/webm;codecs=opus',
            'audio/webm',
            'audio/ogg;codecs=opus',
            'audio/ogg',
            'audio/mp4',
        ];
        for (const mime of candidates) {
            if (MediaRecorder.isTypeSupported(mime)) return { mimeType: mime };
        }
        return {};
    }

    // ── Sending to backend ──────────────────────────────────────────────

    private async _sendAudio(blob: Blob, avSource: string): Promise<void> {
        const speaker = this._buildSpeakerContext(avSource);
        const format = this._blobFormatExtension(blob.type);

        const formData = new FormData();
        formData.append('audio', blob, `recording.${format}`);
        formData.append('speaker', JSON.stringify(speaker));

        try {
            const res = await fetch(`${this.serverUrl}/gm/transcription/submit`, {
                method: 'POST',
                body: formData,
            });

            if (!res.ok) {
                const errText = await res.text().catch(() => '');
                console.warn('[Transcription] Server returned', res.status, errText);
                return;
            }

            const result = await res.json() as TranscriptionEvent;
            console.debug('[Transcription] Result:', result.text);

            // Notify registered callbacks
            for (const cb of this.onTranscript) {
                try { cb(result); } catch (e) { console.error('[Transcription] callback error', e); }
            }

            // Post to Foundry chat if there is text (optional — can be toggled via settings)
            if (result.text && game.settings?.get('ai-gm', 'transcriptionToChat') === true) {
                ChatMessage.create({
                    content: `<em>[${speaker.userName}]</em> ${result.text}`,
                    speaker: { alias: speaker.characterName ?? speaker.userName ?? 'Unknown' },
                });
            }

        } catch (e) {
            console.error('[Transcription] Failed to send audio:', e);
        }
    }

    // ── Speaker context ─────────────────────────────────────────────────

    private _buildSpeakerContext(avSource: string): SpeakerContext {
        const user = game.users?.get(this.localUserId);
        const character = user?.character as any;

        return {
            userId:        this.localUserId,
            userName:      user?.name ?? '',
            isGM:          user?.isGM ?? false,
            characterId:   character?.id ?? null,
            characterName: character?.name ?? null,
            characterType: character?.type ?? null,
            worldId:       game.world?.id ?? null,
            sceneName:     (game.scenes?.current as any)?.name ?? null,
            systemId:      game.system?.id ?? null,
            sessionId:     this.sessionId,
            avSource,
        };
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private _blobFormatExtension(mimeType: string): string {
        if (mimeType.includes('webm'))  return 'webm';
        if (mimeType.includes('ogg'))   return 'ogg';
        if (mimeType.includes('mp4'))   return 'mp4';
        if (mimeType.includes('wav'))   return 'wav';
        return 'webm';
    }
}

// ── Types ─────────────────────────────────────────────────────────────────

export interface TranscriptionEvent {
    transcriptionId: string;
    text: string;
    language?: string;
    durationSeconds?: number;
    speaker?: SpeakerContext;
    recordedAt?: number;
    transcribedAt?: number;
    model?: string;
    error?: string;
}
