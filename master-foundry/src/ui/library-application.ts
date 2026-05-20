/**
 * LibraryApplication — Standalone popup for Book / Compendium ingestion.
 *
 * Wraps LibraryPanel in a dedicated Foundry Application window.
 */

import { LibraryPanel } from './library-panel.js';
import type { PanelContext } from './panel-utils.js';

export class LibraryApplication extends Application {

    private readonly _panel: LibraryPanel;

    constructor(options: any = {}) {
        super(options);
        // eslint-disable-next-line @typescript-eslint/no-this-alias
        const self = this;
        const ctx: PanelContext = {
            get element() { return self.element; },
            render: (force = false) => self.render(force),
            getLastRoll: () => (game as any).aiGM?.featuresApp?.getLastAdventureRoll?.() ?? null,
            getSelectedActorType: () => (game as any).aiGM?.generateApp?.getSelectedActorType?.() ?? 'character',
            getAdventureSessionId: () => (game as any).aiGM?.featuresApp?.getAdventureSessionId?.() ?? null,
        };
        this._panel = new LibraryPanel(ctx);
    }

    static get defaultOptions(): any {
        return foundry.utils.mergeObject(super.defaultOptions, {
            id: 'ai-gm-library',
            title: 'AI GM: Library',
            template: 'modules/ai-gm/templates/library-panel.hbs',
            width: 580,
            height: 540,
            resizable: true,
            classes: ['ai-gm-panel-window'],
        });
    }

    async getData(_options: any = {}): Promise<any> {
        try {
            return await this._panel.getData();
        } catch (e) {
            console.warn('[AI-GM] LibraryApplication.getData failed:', e);
            return {};
        }
    }

    activateListeners(html: any): void {
        super.activateListeners(html);
        this._panel.activateListeners(html);
    }
}

