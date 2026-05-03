/**
 * Plays NPC dialogue audio (base64 WAV) in order, surfacing which NPC is
 * currently speaking so the UI can show an indicator.
 */

import type { NpcDialoguePayload } from '../types/index.js';

export interface AudioPlaybackCallbacks {
    onSpeakingChange?: (npc: NpcDialoguePayload | null) => void;
}

export class AudioPlaybackService {
    private queue: NpcDialoguePayload[] = [];
    private playing = false;
    private currentAudio: HTMLAudioElement | null = null;

    constructor(private readonly cb: AudioPlaybackCallbacks = {}) { }

    enqueue(dialogue: NpcDialoguePayload): void {
        if (!dialogue || !dialogue.audioBase64) return;
        this.queue.push(dialogue);
        if (!this.playing) this.playNext();
    }

    stop(): void {
        if (this.currentAudio) {
            this.currentAudio.pause();
            this.currentAudio.src = '';
            this.currentAudio = null;
        }
        this.queue = [];
        this.playing = false;
        this.cb.onSpeakingChange?.(null);
    }

    private playNext(): void {
        const next = this.queue.shift();
        if (!next) {
            this.playing = false;
            this.cb.onSpeakingChange?.(null);
            return;
        }

        this.playing = true;
        this.cb.onSpeakingChange?.(next);

        const audio = new Audio(`data:audio/wav;base64,${next.audioBase64}`);
        this.currentAudio = audio;

        audio.onended = () => {
            this.currentAudio = null;
            this.playNext();
        };
        audio.onerror = (e) => {
            console.error('[AudioPlayback] failed to play NPC audio', e);
            this.currentAudio = null;
            this.playNext();
        };

        audio.play().catch(err => {
            console.error('[AudioPlayback] play() rejected', err);
            this.currentAudio = null;
            this.playNext();
        });
    }
}
