/**
 * AI Game Master Panel — Orchestrator
 *
 * Delegates all logic to dedicated subpanels and manages UI-level tab routing
 * (primary + secondary tab strips).
 */

import { GeneratePanel } from './generate-panel.js';
import { SessionPanel } from './session-panel.js';
import { LibraryPanel } from './library-panel.js';
import { ConfigPanel } from './config-panel.js';
import { FeaturesPanel } from './features-panel.js';
import type { PanelContext } from './panel-utils.js';

export class AIGameMasterPanel extends Application {

    private readonly _generatePanel: GeneratePanel;
    private readonly _sessionPanel: SessionPanel;
    private readonly _libraryPanel: LibraryPanel;
    private readonly _configPanel: ConfigPanel;
    private readonly _featuresPanel: FeaturesPanel;

    private _pendingPrimaryTab: string | null = null;

    constructor(options: any = {}) {
        super(options);

        // eslint-disable-next-line @typescript-eslint/no-this-alias
        const self = this;
        const ctx: PanelContext = {
            get element() { return self.element; },
            render: (force = false) => self.render(force),
            getLastRoll: () => self._featuresPanel?.getLastAdventureRoll() ?? null,
            getSelectedActorType: () => self._generatePanel?.getSelectedActorType() ?? 'character',
            getAdventureSessionId: () => self._featuresPanel?.getAdventureSessionId() ?? null,
        };

        this._generatePanel = new GeneratePanel(ctx);
        this._sessionPanel = new SessionPanel(ctx);
        this._libraryPanel = new LibraryPanel(ctx);
        this._configPanel = new ConfigPanel(ctx);
        this._featuresPanel = new FeaturesPanel(ctx);

        this._featuresPanel.setSessionPanel(this._sessionPanel);
    }

    static get defaultOptions(): any {
        return foundry.utils.mergeObject(super.defaultOptions, {
            id: 'ai-gm-panel',
            title: 'AI Game Master',
            template: 'modules/ai-gm/templates/ai-game-master-panel.hbs',
            width: 720,
            height: 660,
            resizable: true,
            classes: ['ai-gm-panel-window'],
            tabs: [{
                navSelector: '.ai-gm-tabs',
                contentSelector: '.ai-gm-tab-content',
                initial: 'generator'
            }]
        });
    }

    async getData(_options: any = {}): Promise<any> {
        const [generateData, sessionData, libraryData, configData, featuresData] = await Promise.all([
            this._generatePanel.getData().catch(e => {
                console.warn('[AI-GM] GeneratePanel.getData failed:', e);
                return {};
            }),
            Promise.resolve(this._sessionPanel.getData()),
            this._libraryPanel.getData().catch(e => {
                console.warn('[AI-GM] LibraryPanel.getData failed:', e);
                return {};
            }),
            this._configPanel.getData().catch(e => {
                console.warn('[AI-GM] ConfigPanel.getData failed:', e);
                return {};
            }),
            this._featuresPanel.getData().catch(e => {
                console.warn('[AI-GM] FeaturesPanel.getData failed:', e);
                return {};
            }),
        ]);

        return {
            ...generateData,
            ...sessionData,
            ...libraryData,
            ...configData,
            ...featuresData,
        };
    }

    activateListeners(html: any): void {
        super.activateListeners(html);

        this._wireSecondaryTabs(html);

        this._generatePanel.activateListeners(html);
        this._sessionPanel.activateListeners(html);
        this._libraryPanel.activateListeners(html);
        this._configPanel.activateListeners(html);
        this._featuresPanel.activateListeners(html);

        this._activatePendingPrimaryTab(html);
    }

    async close(options: any = {}): Promise<void> {
        this._sessionPanel.close();
        return super.close(options);
    }

    /** Public entry point to open panel and optionally jump to a primary tab. */
    open(tab: string = 'generator'): void {
        this._pendingPrimaryTab = tab;
        this.render(true);
    }

    /** Called from `createChatMessage` hook to process adventure roll context. */
    async handleAdventureChatMessage(message: any): Promise<void> {
        return this._featuresPanel.handleAdventureChatMessage(message);
    }

    private _activatePendingPrimaryTab(html: any): void {
        const pending = this._pendingPrimaryTab;
        this._pendingPrimaryTab = null;
        if (!pending) return;

        const item = html.find(`.ai-gm-tabs .item[data-tab="${pending}"]`).first();
        if (item.length) {
            item.trigger('click');
        }
    }

    private _wireSecondaryTabs(html: any): void {
        const activateGroup = (container: any, subtab: string): void => {
            container.find('.ai-gm-subtabs .item').removeClass('active');
            container.find(`.ai-gm-subtabs .item[data-subtab="${subtab}"]`).addClass('active');

            container.find('.ai-gm-subtab-content').removeClass('active');
            container.find(`.ai-gm-subtab-content[data-subtab-content="${subtab}"]`).addClass('active');
        };

        const configureSubtabs = (primaryTabId: string): void => {
            const tabContainer = html.find(`.tab[data-tab="${primaryTabId}"]`);
            if (!tabContainer.length) return;

            const nav = tabContainer.find('.ai-gm-subtabs').first();
            if (!nav.length) return;

            nav.find('.item').off('click.ai-gm-subtabs').on('click.ai-gm-subtabs', (ev: any) => {
                ev.preventDefault();
                const subtab = String(ev.currentTarget?.dataset?.subtab || '');
                if (!subtab) return;
                activateGroup(tabContainer, subtab);
            });

            const active = nav.find('.item.active').first();
            const initialSubtab = active.length
                ? String(active.data('subtab'))
                : String(nav.find('.item').first().data('subtab') || '');
            if (initialSubtab) activateGroup(tabContainer, initialSubtab);
        };

        configureSubtabs('generator');
        configureSubtabs('configuration');
    }
}
