/**
 * ConfigApplication — Standalone popup for System / Configuration settings.
 *
 * Wraps ConfigPanel in a dedicated Foundry Application window.
 * Exposes storeReferenceCharacter() for the actor context-menu hook.
 */

import { ConfigPanel } from './config-panel.js';
import type { PanelContext } from './panel-utils.js';

export class ConfigApplication extends Application {

    private readonly _panel: ConfigPanel;

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
        this._panel = new ConfigPanel(ctx);
    }

    static get defaultOptions(): any {
        return foundry.utils.mergeObject(super.defaultOptions, {
            id: 'ai-gm-config',
            title: 'AI GM: Configuration',
            template: 'modules/ai-gm/templates/config-panel.hbs',
            width: 660,
            height: 640,
            resizable: true,
            classes: ['ai-gm-panel-window'],
        });
    }

    async getData(_options: any = {}): Promise<any> {
        try {
            return await this._panel.getData();
        } catch (e) {
            console.warn('[AI-GM] ConfigApplication.getData failed:', e);
            return {};
        }
    }

    activateListeners(html: any): void {
        super.activateListeners(html);
        this._panel.activateListeners(html);
    }

    /** Delegate reference character storage (called from actor context-menu hook). */
    async storeReferenceCharacter(actor: any): Promise<void> {
        return this._panel.storeReferenceCharacter(actor);
    }
}

