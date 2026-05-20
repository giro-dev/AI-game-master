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
        this._wireSubtabs(html);
    }

    private _wireSubtabs(html: any): void {
        const btnSel = '.ai-gm-subtabs .ai-gm-tab-btn, .ai-gm-subtabs .item';

        const activate = (subtab: string): void => {
            html.find(btnSel).removeClass('active');
            html.find(`${btnSel.split(',').map((s: string) => `${s.trim()}[data-subtab="${subtab}"]`).join(',')}`).addClass('active');
            html.find('.ai-gm-subtab-content').removeClass('active');
            html.find(`.ai-gm-subtab-content[data-subtab-content="${subtab}"]`).addClass('active');
        };

        html.find(btnSel).off('click.subtabs').on('click.subtabs', (ev: any) => {
            ev.preventDefault();
            const subtab = String(ev.currentTarget?.dataset?.subtab ?? '');
            if (subtab) activate(subtab);
        });

        const activeBtn = html.find(`${btnSel.split(',').map((s: string) => `${s.trim()}.active`).join(',')}`).first();
        const init = activeBtn.length
            ? String(activeBtn.data('subtab'))
            : String(html.find(btnSel).first().data('subtab') ?? '');
        if (init) activate(init);
    }

    /** Delegate reference character storage (called from actor context-menu hook). */
    async storeReferenceCharacter(actor: any): Promise<void> {
        return this._panel.storeReferenceCharacter(actor);
    }
}

