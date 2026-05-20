/**
 * FeaturesApplication — Standalone popup for Features / Game Director / Transcription.
 *
 * Wraps FeaturesPanel in a dedicated Foundry Application window with
 * internal subtabs for Settings, Game Director, and Transcription.
 * Exposes getLastAdventureRoll() and getAdventureSessionId() for cross-panel context,
 * and handleAdventureChatMessage() for the createChatMessage hook.
 */

import { FeaturesPanel } from './features-panel.js';
import type { SessionPanel } from './session-panel.js';
import type { PanelContext } from './panel-utils.js';

export class FeaturesApplication extends Application {

    private readonly _panel: FeaturesPanel;

    constructor(options: any = {}) {
        super(options);
        // eslint-disable-next-line @typescript-eslint/no-this-alias
        const self = this;
        const ctx: PanelContext = {
            get element() { return self.element; },
            render: (force = false) => self.render(force),
            getLastRoll: () => self._panel?.getLastAdventureRoll?.() ?? null,
            getSelectedActorType: () => (game as any).aiGM?.generateApp?.getSelectedActorType?.() ?? 'character',
            getAdventureSessionId: () => self._panel?.getAdventureSessionId?.() ?? null,
        };
        this._panel = new FeaturesPanel(ctx);
    }

    static get defaultOptions(): any {
        return foundry.utils.mergeObject(super.defaultOptions, {
            id: 'ai-gm-features',
            title: 'AI GM: Features & Adventure',
            template: 'modules/ai-gm/templates/features-panel.hbs',
            width: 680,
            height: 640,
            resizable: true,
            classes: ['ai-gm-panel-window'],
        });
    }

    // ── Cross-panel accessors ──────────────────────────────────────────

    getLastAdventureRoll(): any {
        return this._panel.getLastAdventureRoll();
    }

    getAdventureSessionId(): string | null {
        return this._panel.getAdventureSessionId();
    }

    /** Wire the SessionPanel so FeaturesPanel can collect world-state during narration. */
    setSessionPanel(sp: SessionPanel): void {
        this._panel.setSessionPanel(sp);
    }

    /** Called from createChatMessage hook to forward dice rolls to the Adventure Director. */
    async handleAdventureChatMessage(message: any): Promise<void> {
        return this._panel.handleAdventureChatMessage(message);
    }

    // ── Foundry Application lifecycle ─────────────────────────────────

    async getData(_options: any = {}): Promise<any> {
        try {
            return await this._panel.getData();
        } catch (e) {
            console.warn('[AI-GM] FeaturesApplication.getData failed:', e);
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
            html.find(`${btnSel.split(',').map(s => `${s.trim()}[data-subtab="${subtab}"]`).join(',')}`).addClass('active');
            html.find('.ai-gm-subtab-content').removeClass('active');
            html.find(`.ai-gm-subtab-content[data-subtab-content="${subtab}"]`).addClass('active');
        };

        html.find(btnSel).off('click.subtabs').on('click.subtabs', (ev: any) => {
            ev.preventDefault();
            const subtab = String(ev.currentTarget?.dataset?.subtab ?? '');
            if (subtab) activate(subtab);
        });

        const activeBtn = html.find(`${btnSel.split(',').map(s => `${s.trim()}.active`).join(',')}`).first();
        const init = activeBtn.length
            ? String(activeBtn.data('subtab'))
            : String(html.find(btnSel).first().data('subtab') ?? '');
        if (init) activate(init);
    }
}

