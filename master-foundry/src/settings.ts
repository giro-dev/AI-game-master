/**
 * AI Game Master — Module Settings
 *
 * Registers all Foundry VTT module settings and exposes typed helpers
 * to read their current values at runtime.
 */

export const MODULE_ID = 'ai-gm';
export const DEFAULT_SERVER_URL = 'http://localhost:8080';

export function registerSettings(): void {
    game.settings.register(MODULE_ID, 'serverUrl', {
        name: 'Backend Server URL',
        hint: 'URL of the AI Game Master backend server (e.g. http://localhost:8080).',
        scope: 'world',
        config: true,
        type: String,
        default: DEFAULT_SERVER_URL
    });

    game.settings.register(MODULE_ID, 'enableTranscription', {
        name: 'Enable Voice Transcription',
        hint: 'Allow microphone capture and voice transcription during adventure sessions.',
        scope: 'world',
        config: true,
        type: Boolean,
        default: false
    });

    game.settings.register(MODULE_ID, 'enableGameDirector', {
        name: 'Enable Game Director',
        hint: 'Enable the AI Game Director for narrating adventures in real-time.',
        scope: 'world',
        config: true,
        type: Boolean,
        default: false
    });

    // Legacy setting kept for backward compatibility
    try {
        game.settings.register(MODULE_ID, 'defaultItemPack', {
            name: 'Default Item Pack',
            scope: 'world',
            config: false,
            type: String,
            default: ''
        });
    } catch (_e) { /* already registered */ }
}

export function getServerUrl(): string {
    try {
        return String(game.settings.get(MODULE_ID, 'serverUrl') || DEFAULT_SERVER_URL);
    } catch {
        return DEFAULT_SERVER_URL;
    }
}

export function isTranscriptionEnabled(): boolean {
    try {
        return Boolean(game.settings.get(MODULE_ID, 'enableTranscription'));
    } catch {
        return false;
    }
}

export function isGameDirectorEnabled(): boolean {
    try {
        return Boolean(game.settings.get(MODULE_ID, 'enableGameDirector'));
    } catch {
        return false;
    }
}
