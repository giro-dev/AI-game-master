import type { NpcDialoguePayload } from '../types/index.js';

export interface AudioPlaybackOptions {
    onSpeakingChange?: (npc: NpcDialoguePayload | null) => void;
}

export class AudioPlaybackService {
    private readonly queue: NpcDialoguePayload[] = [];
    private currentAudio: HTMLAudioElement | null = null;
    private currentNpc: NpcDialoguePayload | null = null;

    constructor(private readonly options: AudioPlaybackOptions = {}) {}

    enqueue(payload: NpcDialoguePayload): void {
        if (!payload?.audioUrl && !payload?.audioBase64) {
            this.options.onSpeakingChange?.(null);
            return;
        }
        this.queue.push(payload);
        if (!this.currentAudio) {
            void this.playNext();
        }
    }

    stop(): void {
        this.queue.length = 0;
        if (this.currentAudio) {
            this.currentAudio.pause();
            this.currentAudio.src = '';
            this.currentAudio = null;
        }
        this.currentNpc = null;
        this.options.onSpeakingChange?.(null);
    }

    private async playNext(): Promise<void> {
        const next = this.queue.shift();
        if (!next || (!next.audioUrl && !next.audioBase64)) {
            this.currentAudio = null;
            this.currentNpc = null;
            this.options.onSpeakingChange?.(null);
            return;
        }

        this.currentNpc = next;
        this.options.onSpeakingChange?.(next);

        // Prefer URL (lightweight WebSocket payload); fall back to embedded base64.
        const src = next.audioUrl
            ? next.audioUrl
            : `data:audio/wav;base64,${next.audioBase64}`;

        const audio = new Audio(src);
        this.currentAudio = audio;

        audio.onended = () => {
            this.currentAudio = null;
            this.currentNpc = null;
            this.options.onSpeakingChange?.(null);
            void this.playNext();
        };
        audio.onerror = () => {
            console.warn('[AI-GM Audio] Failed to play audio for', next.npcName ?? next.npcId);
            this.currentAudio = null;
            this.currentNpc = null;
            this.options.onSpeakingChange?.(null);
            void this.playNext();
        };

        try {
            await audio.play();
        } catch (error) {
            console.warn('[AI-GM Audio] Playback blocked or failed:', error);
            this.currentAudio = null;
            this.currentNpc = null;
            this.options.onSpeakingChange?.(null);
            void this.playNext();
        }
    }
}
