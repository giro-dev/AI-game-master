/**
 * GenerateApplication — Standalone popup for Character & Item generation.
 *
 * Wraps GeneratePanel in a dedicated Foundry Application window with its
 * own template, id and title — no shared tab container needed.
 */

import { GeneratePanel } from './generate-panel.js';
import type { PanelContext } from './panel-utils.js';

export class GenerateApplication extends Application {

    private readonly _panel: GeneratePanel;

    constructor(options: any = {}) {
        super(options);
        // eslint-disable-next-line @typescript-eslint/no-this-alias
        const self = this;
        const ctx: PanelContext = {
            get element() { return self.element; },
            render: (force = false) => self.render(force),
            getLastRoll: () => (game as any).aiGM?.featuresApp?.getLastAdventureRoll?.() ?? null,
            getSelectedActorType: () => self._panel?.getSelectedActorType?.() ?? 'character',
            getAdventureSessionId: () => (game as any).aiGM?.featuresApp?.getAdventureSessionId?.() ?? null,
        };
        this._panel = new GeneratePanel(ctx);
    }

    static get defaultOptions(): any {
        return foundry.utils.mergeObject(super.defaultOptions, {
            id: 'ai-gm-generate',
            title: 'AI GM: Generator',
            template: 'modules/ai-gm/templates/generate-panel.hbs',
            width: 680,
            height: 620,
            resizable: true,
            classes: ['ai-gm-panel-window'],
        });
    }

    /** Expose selected actor type for cross-panel context. */
    getSelectedActorType(): string {
        return this._panel.getSelectedActorType();
    }

    async getData(_options: any = {}): Promise<any> {
        try {
            return await this._panel.getData();
        } catch (e) {
            console.warn('[AI-GM] GenerateApplication.getData failed:', e);
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
        const contents = html.find('.ai-gm-panel-body .ai-gm-subtab-content');

        const activate = (subtab: string): void => {
            html.find(btnSel).removeClass('active');
            html.find(`${btnSel.split(',').map(s => `${s.trim()}[data-subtab="${subtab}"]`).join(',')}`).addClass('active');
            // Use jQuery show/hide so inline styles override any CSS conflicts
            contents.hide();
            html.find(`.ai-gm-subtab-content[data-subtab-content="${subtab}"]`).show();
        };

        // Hide all content first so initial state is clean regardless of HTML classes
        contents.hide();

        html.find(btnSel).off('click.subtabs').on('click.subtabs', (ev: any) => {
            ev.preventDefault();
            const subtab = String(ev.currentTarget?.dataset?.subtab ?? '');
            if (subtab) activate(subtab);
        });

        // Activate the tab marked active in the HTML, or the first one
        const activeBtn = html.find(`${btnSel.split(',').map(s => `${s.trim()}.active`).join(',')}`).first();
        const init = activeBtn.length
            ? String(activeBtn.data('subtab'))
            : String(html.find(btnSel).first().data('subtab') ?? '');
        if (init) activate(init);
    }
}

