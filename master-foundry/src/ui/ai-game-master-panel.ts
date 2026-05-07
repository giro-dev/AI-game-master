/**
 * AI Game Master Panel — Orchestrator
 *
 * Delegates all tab logic to dedicated subpanels.  This class only handles
 * Application lifecycle, merging getData results, and routing
 * activateListeners calls to the appropriate submodule.
 *
 * Tabs: Generate · Chat · Library · Configuration · Features
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

    constructor(options: any = {}) {
        super(options);

        // Build the context object that subpanels use to trigger re-renders
        // and access the host Application element.  We capture `this` via a
        // local alias so the arrow function closures remain valid even after
        // the constructor returns.
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
        this._sessionPanel  = new SessionPanel(ctx);
        this._libraryPanel  = new LibraryPanel(ctx);
        this._configPanel   = new ConfigPanel(ctx);
        this._featuresPanel = new FeaturesPanel(ctx);

        // Give the features panel a reference to the session panel so it can
        // delegate world-state collection during adventure narration.
        this._featuresPanel.setSessionPanel(this._sessionPanel);
    }

    /* ------------------------------------------------------------------ */
    /*  Foundry Application boilerplate                                     */
    /* ------------------------------------------------------------------ */

    static get defaultOptions(): any {
        return foundry.utils.mergeObject(super.defaultOptions, {
            id: 'ai-gm-panel',
            title: 'AI Game Master',
            template: 'modules/ai-gm/templates/ai-game-master-panel.hbs',
            width: 700,
            height: 640,
            resizable: true,
            classes: ['ai-gm-panel-window'],
            tabs: [{
                navSelector: '.ai-gm-tabs',
                contentSelector: '.ai-gm-tab-content',
                initial: 'generate'
            }]
        });
    }

    /* ------------------------------------------------------------------ */
    /*  getData — merge contributions from all subpanels                   */
    /* ------------------------------------------------------------------ */

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

    /* ------------------------------------------------------------------ */
    /*  activateListeners — delegate to each subpanel                      */
    /* ------------------------------------------------------------------ */

    activateListeners(html: any): void {
        super.activateListeners(html);
        this._generatePanel.activateListeners(html);
        this._sessionPanel.activateListeners(html);
        this._libraryPanel.activateListeners(html);
        this._configPanel.activateListeners(html);
        this._featuresPanel.activateListeners(html);
    }

    /* ------------------------------------------------------------------ */
    /*  close — clean up subpanel resources                                */
    /* ------------------------------------------------------------------ */

    async close(options: any = {}): Promise<void> {
        this._sessionPanel.close();
        return super.close(options);
    }

    /* ------------------------------------------------------------------ */
    /*  Public API consumed by main.ts hooks                               */
    /* ------------------------------------------------------------------ */

    /**
     * Handle an incoming Foundry chat message for adventure roll interpretation.
     * Called from the `createChatMessage` hook registered in main.ts.
     */
    async handleAdventureChatMessage(message: any): Promise<void> {
        return this._featuresPanel.handleAdventureChatMessage(message);
    }
}
