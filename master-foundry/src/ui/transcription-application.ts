/**
 * TranscriptionApplication — Standalone popup for Voice Transcription settings.
 *
 * Simple panel for toggling transcription and showing its status/requirements.
 */

import { isTranscriptionEnabled, getServerUrl, MODULE_ID } from '../settings.js';

export class TranscriptionApplication extends Application {

    static get defaultOptions(): any {
        return foundry.utils.mergeObject(super.defaultOptions, {
            id: 'ai-gm-transcription',
            title: 'AI GM: Voice Transcription',
            template: 'modules/ai-gm/templates/transcription-panel.hbs',
            width: 460,
            height: 380,
            resizable: false,
            classes: ['ai-gm-panel-window'],
        });
    }

    getData(_options: any = {}): any {
        return {
            enableTranscription: isTranscriptionEnabled(),
            serverUrl: getServerUrl(),
        };
    }

    activateListeners(html: any): void {
        super.activateListeners(html);

        html.find('#ai-gm-enable-transcription-standalone').on('change', async (ev: any) => {
            await game.settings.set(MODULE_ID, 'enableTranscription', ev.target.checked);
            // Re-render this popup and also refresh features popup if open
            this.render(false);
            (game as any).aiGM?.featuresApp?.render(false);
        });
    }
}

