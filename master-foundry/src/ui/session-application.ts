/**
 * SessionApplication — Standalone popup for Chat / Session with the AI.
 *
 * Wraps SessionPanel in a dedicated Foundry Application window.
 */

import { SessionPanel } from './session-panel.js';
import type { PanelContext } from './panel-utils.js';

export class SessionApplication extends Application {

    private readonly _panel: SessionPanel;

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
        this._panel = new SessionPanel(ctx);
    }

    static get defaultOptions(): any {
        return foundry.utils.mergeObject(super.defaultOptions, {
            id: 'ai-gm-session',
            title: 'AI GM: Chat',
            template: 'modules/ai-gm/templates/session-panel.hbs',
            width: 600,
            height: 560,
            resizable: true,
            classes: ['ai-gm-panel-window'],
        });
    }

    /** Expose the inner SessionPanel for cross-panel world-state collection. */
    getPanel(): SessionPanel {
        return this._panel;
    }

    getData(_options: any = {}): any {
        return this._panel.getData();
    }

    activateListeners(html: any): void {
        super.activateListeners(html);
        this._panel.activateListeners(html);
    }

    async close(options: any = {}): Promise<void> {
        this._panel.close();
        return super.close(options);
    }
}

