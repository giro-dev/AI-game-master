/**
 * AI Game Master Panel
 * Central hub for all AI-powered game master tools in Foundry VTT.
 *
 * Tabs: Characters · Items · Library · Session · System
 */

import { FieldTreeBuilder } from '../utils/field-tree-builder.js';
import { CharacterDataSanitizer } from '../utils/character-data-sanitizer.js';
import { CharacterGenerationService } from '../services/character-generation-service.js';
import { AudioCaptureService } from '../services/audio-capture-service.js';
import { AudioPlaybackService } from '../services/audio-playback-service.js';
import type { SystemSkill } from '../skills/system-skill.js';
import type {
    FieldDefinition,
    CharacterData,
    ValidationError,
    ChatEntry,
    BookInfo,
    VTTAction,
    AdventureSummary,
    AdventureSavedSessionSummary,
    AdventureNpcInfo,
    AdventureSceneInfo,
    AdventureRollInfo,
    AdventureStartResponse,
    NpcDialoguePayload,
    IntentConfirmationPayload,
    DirectorNarrationPayload,
    AdventureStateUpdatePayload
} from '../types/index.js';

const API = 'http://localhost:8080';

export class AIGameMasterPanel extends Application {

    // ── Character tab state ──
    private selectedActorType: string = 'character';
    private actorFields: FieldDefinition[] = [];
    private selectedFields: Set<string> = new Set();
    private characterData: CharacterData | null = null;
    private validationErrors: ValidationError[] = [];

    // ── Session tab state ──
    private chatHistory: ChatEntry[] = [];
    private selectedTokenIds: Set<string> = new Set();
    private _controlTokenHookId: number | null = null;

    // ── Library tab state ──
    private books: BookInfo[] = [];

    // ── Adventure tab state ──
    private adventures: AdventureSummary[] = [];
    private adventureSavedSessions: AdventureSavedSessionSummary[] = [];
    private selectedAdventureId: string | null = null;
    private adventureSessionId: string | null = null;
    private adventureSessionName: string | null = null;
    private adventureSessionParticipants: string[] = [];
    private adventureSessionSummary: string | null = null;
    private adventureScene: AdventureSceneInfo | null = null;
    private discoveredClues: string[] = [];
    private metNpcs: string[] = [];
    private tensionLevel: number = 0;
    private adventureNpcs: AdventureNpcInfo[] = [];
    private adventureLog: string[] = [];
    private lastAdventureRoll: AdventureRollInfo | null = null;
    private processedAdventureRollMessageIds: Set<string> = new Set();
    private speakingNpc: NpcDialoguePayload | null = null;
    private isRecording: boolean = false;
    private isProcessing: boolean = false;
    private _audioCapture: AudioCaptureService | null = null;
    private _audioPlayback: AudioPlaybackService | null = null;
    private _adventureHandlersRegistered: boolean = false;
    private _generationReferenceCharacter: any | null = null;

    // ── Services ──
    private readonly _charService: CharacterGenerationService;

    constructor(options: any = {}) {
        super(options);
        this._charService = new CharacterGenerationService(API);
    }

    /* ------------------------------------------------------------------ */
    /*  Foundry Application boilerplate                                    */
    /* ------------------------------------------------------------------ */

    static get defaultOptions(): any {
        return foundry.utils.mergeObject(super.defaultOptions, {
            id: 'ai-gm-panel',
            title: 'AI Game Master',
            template: 'modules/ai-gm/templates/ai-game-master-panel.hbs',
            width: 680,
            height: 620,
            resizable: true,
            classes: ['ai-gm-panel-window'],
            tabs: [{
                navSelector: '.ai-gm-tabs',
                contentSelector: '.ai-gm-tab-content',
                initial: 'characters'
            }]
        });
    }

    /* ------------------------------------------------------------------ */
    /*  getData – feed all tabs                                            */
    /* ------------------------------------------------------------------ */

    async getData(_options: any = {}): Promise<any> {
        try {
            this._ensureFieldsLoaded();
        } catch (e) {
            console.warn('[AI-GM Panel] Field loading failed:', e);
        }

        await this._refreshBooks();
        await this._refreshAdventures();
        await this._refreshAdventureSessions();

        const ext = game.aiGM?.blueprintGenerator?.schemaExtractor;
        let actorTypes: string[] = ['character'];
        let actorTypeLabels: Record<string, string> = {};
        let itemTypes: string[] = [];

        try {
            actorTypes = ext?.getActorTypes() ?? ['character'];
            actorTypeLabels = ext?.getActorTypeLabels() ?? {};
            itemTypes = ext?.getItemTypes() ?? [];
        } catch (e) {
            console.warn('[AI-GM Panel] Schema extraction failed:', e);
        }

        let itemPacks: any[] = [];
        try {
            itemPacks = game.packs
                ?.filter((p: any) => p.documentName === 'Item')
                ?.map((p: any) => ({
                    id: p.collection,
                    label: p.metadata?.label ?? `${p.metadata?.packageName}.${p.metadata?.name}`
                })) ?? [];
        } catch (e) {
            console.warn('[AI-GM Panel] Pack enumeration failed:', e);
        }

        let knowledgePacks: any[] = [];
        try {
            knowledgePacks = game.packs
                ?.map((p: any) => ({
                    id: p.collection,
                    label: p.metadata?.label ?? `${p.metadata?.packageName}.${p.metadata?.name}`,
                    documentName: p.documentName ?? 'Document',
                    source: p.metadata?.packageName ?? 'world'
                }))
                ?.sort((a: any, b: any) => {
                    const labelCmp = String(a.label).localeCompare(String(b.label));
                    if (labelCmp !== 0) return labelCmp;
                    return String(a.documentName).localeCompare(String(b.documentName));
                }) ?? [];
        } catch (e) {
            console.warn('[AI-GM Panel] Knowledge pack enumeration failed:', e);
        }

        // Fetch reference character status (skip during active adventure to reduce noise)
        let referenceCharacter: any = null;
        if (!this.adventureSessionId) {
            try {
                const refRes = await fetch(`${API}/gm/character/reference/${encodeURIComponent(game.system.id)}/${encodeURIComponent(this.selectedActorType)}`);
                if (refRes.ok) {
                    const refData = await refRes.json();
                    referenceCharacter = {
                        label: refData.label,
                        actorType: refData.actorType,
                        itemCount: refData.items?.length ?? 0,
                        capturedAt: refData.capturedAt ? new Date(refData.capturedAt).toLocaleString() : null,
                    };
                }
            } catch (_e) { /* server unreachable – that's fine */ }
        }

        // Skill registry data
        const skills = game.aiGM?.skillRegistry?.getAllSkills() ?? [];
        const availableReferenceActors = Array.from(game.actors ?? [])
            .map((actor: any) => ({
                id: actor.id,
                name: actor.name ?? 'Unnamed',
                type: actor.type ?? 'unknown',
                itemCount: actor.items?.size ?? 0
            }))
            .sort((a, b) => {
                const nameCmp = String(a.name).localeCompare(String(b.name));
                if (nameCmp !== 0) return nameCmp;
                return String(a.type).localeCompare(String(b.type));
            });

        return {
            // Characters tab
            actorTypes: actorTypes.map(id => ({
                id,
                label: actorTypeLabels[id] ?? id,
                selected: id === this.selectedActorType
            })),
            fieldTreeHTML: FieldTreeBuilder.buildTree(this.actorFields, this.selectedFields),

            // Items tab
            itemPacks,

            // Library tab
            books: this.books.map((book: any) => ({
                ...book,
                displayTitle: book.bookTitle ?? book.title ?? 'Untitled knowledge source',
                sourceLabel: book.sourceType === 'compendium' ? 'Compendium' : 'PDF'
            })),
            knowledgePacks,

            // Session tab
            chatHistory: this.chatHistory,

            // System tab
            systemId: game.system?.id ?? 'unknown',
            systemTitle: game.system?.title ?? 'Unknown System',
            foundryVersion: game.version ?? '',
            actorTypeNames: actorTypes,
            itemTypeNames: itemTypes,
            profile: (await game.aiGM?.snapshotSender?.getProfile()) ?? null,
            wsConnected: game.aiGM?.wsClient?.isConnected() ?? false,
            serverUrl: API,
            referenceCharacter,
            availableReferenceActors,

            // System skills
            skills: skills.map((s: SystemSkill) => ({
                id: s.id,
                name: s.name,
                description: s.description ?? '',
                systemId: s.systemId,
                worldId: s.worldId ?? '',
                enabled: s.enabled !== false
            })),

            // Adventure tab
            adventures: this.adventures.map(a => ({
                ...a,
                selected: a.id === this.selectedAdventureId
            })),
            adventureSavedSessions: this.adventureSavedSessions.map(session => ({
                ...session,
                participantNamesText: (session.participantNames ?? []).join(', '),
                updatedAtLabel: this._formatAdventureDate(session.updatedAt),
                createdAtLabel: this._formatAdventureDate(session.createdAt)
            })),
            adventureSelected: !!this.selectedAdventureId,
            adventureSessionActive: !!this.adventureSessionId,
            adventureSessionId: this.adventureSessionId,
            adventureSessionName: this.adventureSessionName,
            adventureSessionParticipants: this.adventureSessionParticipants,
            adventureSessionSummary: this.adventureSessionSummary,
            adventureScene: this.adventureScene,
            discoveredClues: this.discoveredClues,
            metNpcs: this.metNpcs,
            tensionLevel: this.tensionLevel,
            tensionMax: 10,
            tensionPct: Math.max(0, Math.min(100, Math.round((this.tensionLevel / 10) * 100))),
            adventureNpcs: this.adventureNpcs,
            adventureLog: this.adventureLog,
            speakingNpc: this.speakingNpc,
            isRecording: this.isRecording,
            isProcessing: this.isProcessing
        };
    }

    /* ------------------------------------------------------------------ */
    /*  activateListeners                                                  */
    /* ------------------------------------------------------------------ */

    activateListeners(html: any): void {
        super.activateListeners(html);

        // ── Characters tab ──
        html.find('#gm-actor-type').on('change', this._onActorTypeChange.bind(this));
        html.find('[data-action="select-all-fields"]').on('click', () => this._toggleAllFields(html, true));
        html.find('[data-action="deselect-all-fields"]').on('click', () => this._toggleAllFields(html, false));
        html.find('#gm-field-tree').on('change', '.field-checkbox', this._onFieldToggle.bind(this));
        html.find('[data-action="generate-character"]').on('click', this._onGenerateCharacter.bind(this));
        html.find('[data-action="view-blueprint"]').on('click', this._onViewBlueprint.bind(this));

        // ── Items tab ──
        html.find('[data-action="generate-items"]').on('click', this._onGenerateItems.bind(this));

        // ── Library tab ──
        html.find('[data-action="upload-book"]').on('click', this._onUploadBook.bind(this));
        html.find('[data-action="ingest-compendium"]').on('click', this._onIngestCompendium.bind(this));
        html.find('[data-action="delete-book"]').on('click', this._onDeleteBook.bind(this));

        // ── Session tab ──
        html.find('[data-action="ask-ai"]').on('click', this._onAskAI.bind(this));
        html.find('#gm-session-prompt').on('keydown', (ev: any) => {
            if (ev.key === 'Enter' && !ev.shiftKey) { ev.preventDefault(); this._onAskAI(ev); }
        });

        // Token context selector
        this._refreshSelectedTokens(html);
        this._syncSelectedTokensFromCanvas({ onlyIfEmpty: true });
        this._refreshSceneTokenList(html);

        html.find('#gm-scene-tokens').on('change', '.scene-token-checkbox', this._onSceneTokenToggle.bind(this));
        html.find('[data-action="sync-tokens"]').on('click', () => {
            this._syncSelectedTokensFromCanvas({ overwrite: true });
            this._refreshSceneTokenList(html);
        });
        html.find('[data-action="clear-tokens"]').on('click', () => {
            this.selectedTokenIds.clear();
            this._refreshSceneTokenList(html);
        });

        // Refresh canvas selection pills whenever token control changes
        if (this._controlTokenHookId !== null) Hooks.off('controlToken', this._controlTokenHookId);
        this._controlTokenHookId = Hooks.on('controlToken', () => {
            this._refreshSelectedTokens(html);
            // If the user hasn't picked anything yet, seed from canvas selection.
            if (this.selectedTokenIds.size === 0) {
                this._syncSelectedTokensFromCanvas({ onlyIfEmpty: true });
                this._refreshSceneTokenList(html);
            }
        });

        // ── System tab ──
        html.find('[data-action="relearn-system"]').on('click', this._onRelearnSystem.bind(this));
        html.find('[data-action="reconnect-ws"]').on('click', this._onReconnectWS.bind(this));
        html.find('[data-action="clear-reference"]').on('click', this._onClearReference.bind(this));
        html.find('[data-action="set-reference-character"]').on('click', this._onSetReferenceCharacter.bind(this));

        // ── Skill management ──
        html.find('[data-action="import-skill"]').on('click', this._onImportSkill.bind(this));
        html.find('[data-action="create-skill"]').on('click', this._onCreateSkill.bind(this));
        html.find('[data-action="export-skills"]').on('click', this._onExportSkills.bind(this));
        html.find('[data-action="toggle-skill"]').on('click', this._onToggleSkill.bind(this));
        html.find('[data-action="edit-skill"]').on('click', this._onEditSkill.bind(this));
        html.find('[data-action="delete-skill"]').on('click', this._onDeleteSkill.bind(this));

        // Dynamic result-card buttons (delegated)
        html.find('#char-result').on('click', '[data-action="create-character"]', this._onCreateCharacter.bind(this));
        html.find('#char-result').on('click', '[data-action="export-json"]', this._onExportJSON.bind(this));

        // ── Adventure tab ──
        html.find('#adventure-select').on('change', this._onAdventureSelectChange.bind(this));
        html.find('[data-action="upload-adventure"]').on('click', this._onUploadAdventure.bind(this));
        html.find('[data-action="start-adventure"]').on('click', this._onStartAdventure.bind(this));
        html.find('[data-action="resume-adventure"]').on('click', this._onResumeAdventure.bind(this));
        html.find('[data-action="toggle-mic"]').on('click', this._onToggleMic.bind(this));
        html.find('[data-action="end-adventure"]').on('click', this._onEndAdventure.bind(this));
        html.find('[data-action="send-adventure-text"]').on('click', this._onSendAdventureText.bind(this));
        html.find('#adventure-text-input').on('keydown', (ev: any) => { if (ev.key === 'Enter') this._onSendAdventureText(ev); });
        html.find('[data-action="create-adventure-npc"]').on('click', this._onCreateAdventureNpc.bind(this));

        this._registerAdventureWsHandlers();
    }

    /* ================================================================== */
    /*  CHARACTERS TAB                                                     */
    /* ================================================================== */

    private _ensureFieldsLoaded(): void {
        if (this.actorFields.length > 0) return;
        try {
            const schema = game.aiGM?.blueprintGenerator?.schemaExtractor?.extractActorType(this.selectedActorType);
            if (!schema) return;
            this.actorFields = schema.fields;
            this.selectedFields = new Set(this.actorFields.map((f: FieldDefinition) => f.path));
        } catch (e) {
            console.warn('[AI-GM Panel] Could not load fields:', e);
        }
    }

    private _normalizeKey(s: string): string {
        return (s || '')
            .toLowerCase()
            .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
            .replace(/[^a-z0-9]/g, '');
    }

    private _findExistingEmbeddedItem(actor: any, itemData: Record<string, any>): any | null {
        const type = itemData?.type;
        if (!type) return null;

        const candidates: any[] = (Array.from(actor.items ?? []) as any[]).filter((i: any) => i?.type === type);
        if (candidates.length === 0) return null;

        const aiName = this._normalizeKey(itemData?.name ?? '');
        const aiIdentifier = this._normalizeKey(itemData?.system?.identifier ?? itemData?.flags?.core?.sourceId ?? '');

        for (const c of candidates as any[]) {
            const candName = this._normalizeKey(c?.name ?? '');
            const candIdentifier = this._normalizeKey(c?.system?.identifier ?? c?.flags?.core?.sourceId ?? '');

            if (aiName && (aiName === candName || aiName.includes(candName) || candName.includes(aiName))) return c;
            if (aiIdentifier && (aiIdentifier === candIdentifier || aiIdentifier.includes(candIdentifier) || candIdentifier.includes(aiIdentifier))) return c;
        }

        return null;
    }

    private async _upsertGeneratedItems(actor: any, items: Array<Record<string, any>>): Promise<void> {
        const updates: any[] = [];
        const creates: any[] = [];

        for (const item of items) {
            const existing = this._findExistingEmbeddedItem(actor, item);
            if (existing) {
                const update: Record<string, any> = { _id: existing.id };
                if (item.name) update.name = item.name;
                if (item.img) update.img = item.img;
                if (item.system) update.system = item.system;
                updates.push(update);
            } else {
                creates.push(item);
            }
        }

        if (updates.length > 0) {
            await actor.updateEmbeddedDocuments('Item', updates);
        }
        if (creates.length > 0) {
            await actor.createEmbeddedDocuments('Item', creates);
        }
    }

    private _onActorTypeChange(ev: any): void {
        this.selectedActorType = ev.currentTarget.value;
        try {
            const schema = game.aiGM.blueprintGenerator.schemaExtractor.extractActorType(this.selectedActorType);
            this.actorFields = schema.fields;
            this.selectedFields = new Set(this.actorFields.map((f: FieldDefinition) => f.path));
        } catch (e) {
            console.error('[AI-GM Panel] Field load error:', e);
        }
        this.render(false);
    }

    private _toggleAllFields(html: any, selectAll: boolean): void {
        if (selectAll) {
            this.selectedFields = new Set(this.actorFields.map((f: FieldDefinition) => f.path));
        } else {
            this.selectedFields.clear();
        }
        html.find('.field-checkbox').prop('checked', selectAll);
    }

    private _onFieldToggle(ev: any): void {
        const path: string = ev.currentTarget.dataset.fieldPath;
        if (ev.currentTarget.checked) this.selectedFields.add(path);
        else this.selectedFields.delete(path);
    }

    private async _onGenerateCharacter(_ev: any): Promise<void> {
        const html = this.element;
        const prompt: string = html.find('#gm-char-prompt').val()?.trim();
        const language: string = html.find('#gm-char-lang').val() || 'ca';

        if (!prompt) return ui.notifications.warn('Please enter a character description.');
        if (this.selectedFields.size === 0) return ui.notifications.warn('Select at least one field.');

        const btn = html.find('[data-action="generate-character"]');
        btn.prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Generating…');
        this._showProgress(html, 'char', 'Starting generation…', 5);

        // WS progress handler
        const progressHandler = (data: any): void => {
            if (data?.currentStep) this._showProgress(html, 'char', data.currentStep, data.progress ?? 50);
        };
        game.aiGM?.wsClient?.on('onCharacterGenerationStarted', progressHandler);

        try {
            const selectedArr = Array.from(this.selectedFields);
            let blueprint = game.aiGM.blueprintGenerator.generateAIBlueprint(this.selectedActorType, selectedArr);

            try {
                const itemTypes = (blueprint as any)?.availableItems?.map((i: any) => i?.type).filter(Boolean) ?? [];
                console.log(`[AI-GM] Blueprint availableItems: ${itemTypes.length}`, itemTypes);
            } catch (_e) {
                console.log('[AI-GM] Blueprint availableItems: <unavailable>');
            }

            // Enhance with profile
            if (game.aiGM.postProcessor) blueprint = game.aiGM.postProcessor.enhanceBlueprint(blueprint);

            const sessionId: string | null = game.aiGM?.wsClient?.getSessionId() ?? null;
            const storedRefChar = await this._fetchReferenceCharacter();
            const referenceCharacter = storedRefChar ?? this._buildImplicitReferenceCharacter();
            this._generationReferenceCharacter = referenceCharacter;

            if (referenceCharacter) {
                console.log(`[AI-GM] Using ${storedRefChar ? 'stored' : 'implicit'} reference character "${referenceCharacter.label}"`);
            }

            const request = this._charService.buildRequest({
                prompt,
                actorType: this.selectedActorType,
                blueprint,
                language,
                sessionId,
                worldId: game.world?.id ?? null,
                referenceCharacter
            });
            const charData = await this._charService.generateCharacter(request);

            try {
                const returnedTypes = (charData as any)?.items?.map((i: any) => i?.type).filter(Boolean) ?? [];
                console.log(`[AI-GM] Backend returned items: ${returnedTypes.length}`, returnedTypes);
            } catch (_e) {
                console.log('[AI-GM] Backend returned items: <unavailable>');
            }

            this.characterData = CharacterDataSanitizer.sanitize(charData);

            // Diagnostic: check if habilidades/atributos survived sanitization
            const sysKeys = Object.keys(this.characterData?.actor?.system ?? {});
            console.log('[AI-GM] Post-sanitize system keys:', sysKeys);
            if (this.characterData?.actor?.system?.habilidades) {
                const habKeys = Object.keys(this.characterData.actor.system.habilidades);
                const habSample = habKeys.slice(0, 3).map(k => `${k}=${JSON.stringify(this.characterData!.actor.system.habilidades[k]?.value)}`);
                console.log(`[AI-GM] Post-sanitize habilidades: ${habKeys.length} keys, sample: ${habSample.join(', ')}`);
            } else {
                console.warn('[AI-GM] No habilidades in post-sanitize system data');
            }

            this.validationErrors = [];

            // Validate
            if (game.aiGM.postProcessor && this.characterData) {
                this.validationErrors = game.aiGM.postProcessor.validate(this.characterData, blueprint);
            }

            this._renderCharacterResult(html);
            this._hideProgress(html, 'char');
            ui.notifications.info('Character generated!');
        } catch (e: any) {
            console.error('[AI-GM Panel] Generation error:', e);
            ui.notifications.error(`Generation failed: ${e.message}`);
            this._hideProgress(html, 'char');
        } finally {
            game.aiGM?.wsClient?.off('onCharacterGenerationStarted', progressHandler);
            btn.prop('disabled', false).html('<i class="fas fa-magic"></i> Generate Character');
        }
    }

    private _renderCharacterResult(html: any): void {
        const container = html.find('#char-result');
        if (!this.characterData) { container.html(''); return; }

        let warningsHTML = '';
        if (this.validationErrors.length > 0) {
            warningsHTML = `<div class="validation-warnings"><strong>Warnings:</strong><ul>${
                this.validationErrors.map(e => `<li>${e.field}: ${e.message}</li>`).join('')
            }</ul></div>`;
        }

        container.html(`
            <div class="result-card">
                <h4><i class="fas fa-user"></i> ${this.characterData.actor?.name ?? 'Character'}</h4>
                ${warningsHTML}
                <div class="btn-row">
                    <button type="button" class="btn btn-success btn-block" data-action="create-character">
                        <i class="fas fa-user-plus"></i> Create in Foundry
                    </button>
                    <button type="button" class="btn" data-action="export-json">
                        <i class="fas fa-download"></i> JSON
                    </button>
                </div>
            </div>
        `);
    }

    private async _onCreateCharacter(): Promise<void> {
        if (!this.characterData) return ui.notifications.warn('Generate a character first.');
        try {
            const actorData = this.characterData.actor;
            const aiSystemData = actorData.system ?? {};

            // ── Defensive: ensure required fields are present ──
            const actorName = actorData.name
                || (aiSystemData as any).nombre
                || (aiSystemData as any).nom
                || (aiSystemData as any).concept
                || 'AI Character';
            const actorType = actorData.type || this.selectedActorType || 'character';

            if (!actorData.name) {
                console.warn('[AI-GM] Actor name was undefined, using fallback:', actorName,
                    'Keys in actor:', Object.keys(actorData),
                    'Keys in system:', Object.keys(aiSystemData));
            }

            // Fetch reference character (if stored) for structural guidance
            const refChar = await this._fetchReferenceCharacter() ?? this._generationReferenceCharacter;
            if (refChar) {
                console.log(`[AI-GM] Using reference character "${refChar.label}" for structural guidance`);
            }

            // ── Phase 1: Create a minimal actor so the system adds its defaults ──
            // (Many systems auto-create skill/feature Items only when no system data is given)
            const minimalActor = await Actor.create({
                name: actorName,
                type: actorType,
                img: actorData.img || 'icons/svg/mystery-man.svg'
            });
            if (!minimalActor) throw new Error('Actor creation failed');

            const defaultItemCount = minimalActor.items.size;
            console.log(`[AI-GM] Minimal actor created with ${defaultItemCount} default items`);
            if (defaultItemCount > 0) {
                const itemNames = Array.from(minimalActor.items).map((i: any) => `${i.type}:${i.name}`);
                console.log(`[AI-GM] Default items: ${itemNames.join(', ')}`);
            }

            // ── Phase 2: Update the actor with the AI-generated system data ──
            // Use dot-notation paths for maximum compatibility with template-based systems
            if (Object.keys(aiSystemData).length > 0) {
                console.log('[AI-GM] aiSystemData top-level keys:', Object.keys(aiSystemData));
                if ((aiSystemData as any).habilidades) {
                    console.log('[AI-GM] habilidades in aiSystemData:', JSON.stringify((aiSystemData as any).habilidades));
                }
                if ((aiSystemData as any).atributos) {
                    console.log('[AI-GM] atributos in aiSystemData:', JSON.stringify((aiSystemData as any).atributos));
                }

                // Flatten nested AI system data into dot-notation paths for reliable updates.
                // Use the actor's existing system as a structural template: if the AI returns
                // a flat primitive (e.g. habilidades.interaccion = 8) but the actor expects
                // an object with a .value sub-field, route the update to .value automatically.
                const existingSystem = (minimalActor as any).system ?? {};
                const flatUpdate: Record<string, any> = {};
                this._flattenStructureAware(aiSystemData, 'system', flatUpdate, existingSystem);
                console.log(`[AI-GM] Flattened update: ${Object.keys(flatUpdate).length} paths`);
                const samplePaths = Object.keys(flatUpdate).slice(0, 15);
                console.log('[AI-GM] Sample update paths:', samplePaths.map(p => `${p}=${flatUpdate[p]}`).join(', '));

                await minimalActor.update(flatUpdate);
                console.log('[AI-GM] Actor updated with AI system data (dot-notation)');

                // Verify critical fields after update
                const postHab = (minimalActor as any).system?.habilidades;
                if (postHab) {
                    const vals = Object.entries(postHab)
                        .filter(([, v]: [string, any]) => v && typeof v === 'object' && 'value' in v)
                        .map(([k, v]: [string, any]) => `${k}=${v.value}`)
                        .join(', ');
                    console.log(`[AI-GM] Post-update habilidades: ${vals}`);
                }
                const postAttr = (minimalActor as any).system?.atributos;
                if (postAttr) {
                    const vals = Object.entries(postAttr)
                        .filter(([, v]: [string, any]) => v && typeof v === 'object' && 'value' in v)
                        .map(([k, v]: [string, any]) => `${k}=${v.value}`)
                        .join(', ');
                    console.log(`[AI-GM] Post-update atributos: ${vals}`);
                }
            }

            // ── Phase 3: Sync AI skill values to embedded Items ──
            // Uses reference character items as a structural guide when available
            if (minimalActor.items.size > 0) {
                await this._syncAISkillsToItems(minimalActor, aiSystemData, refChar);
            }

            // ── Phase 4: Add AI-generated equipment/other items ──
            // Use reference character item structures to fix AI-generated items if needed
            if (this.characterData.items?.length) {
                const fixedItems = refChar
                    ? this._alignItemsToReference(this.characterData.items, refChar.items ?? [])
                    : this.characterData.items;
                await this._upsertGeneratedItems(minimalActor, fixedItems);
            }

            ui.notifications.info(`Created: ${minimalActor.name}`);
            minimalActor.sheet.render(true);
            this.characterData = null;
            this._generationReferenceCharacter = null;
            this.render(false);
        } catch (e: any) {
            console.error('[AI-GM] Create character failed:', e);
            ui.notifications.error(`Create failed: ${e.message}`);
        }
    }


    /**
     * After creation, sync AI-generated numeric skill values from the raw AI
     * system data to the corresponding embedded Item documents.
     *
     * When a reference character is available, uses its items to determine
     * the exact `system.*` field paths that hold skill values (instead of
     * guessing with heuristics).
     *
     * @param actor  The created Foundry Actor (with embedded Items from system defaults)
     * @param aiSystemData  The raw AI-generated system object (plain JS, not a Foundry proxy)
     * @param refChar  Optional reference character for structural guidance
     */
    private async _syncAISkillsToItems(actor: any, aiSystemData: Record<string, any>, refChar?: any): Promise<void> {
        if (!actor.items.size) return;

        // ── Strategy 1: Reference-based exact mapping ──
        // If we have a reference character, build a precise mapping from item name → system field paths
        if (refChar?.items?.length) {
            const updates = this._buildReferenceBasedUpdates(actor, aiSystemData, refChar.items);
            if (updates.length > 0) {
                console.log(`[AI-GM] Reference-based sync: ${updates.length} items matched`);
                await actor.updateEmbeddedDocuments('Item', updates);
                return;
            }
            console.log('[AI-GM] Reference-based sync found no matches, falling back to heuristic');
        }

        // ── Strategy 2: Heuristic fallback (original approach) ──
        // Collect candidate skill maps from the RAW AI data (not actor.system which is a proxy)
        const candidateMaps = this._collectSkillDataMaps(aiSystemData);
        if (candidateMaps.length === 0) {
            console.log('[AI-GM] No skill data maps found in AI system data');
            return;
        }
        console.log(`[AI-GM] Found ${candidateMaps.length} candidate skill maps: ${candidateMaps.map(m => m.path).join(', ')}`);

        const updates: any[] = [];

        for (const item of actor.items) {
            for (const { map, path } of candidateMaps) {
                const matchKey = this._fuzzyMatchItemToKey(item, Object.keys(map));
                if (matchKey === null) continue;

                const aiValue = map[matchKey];
                if (typeof aiValue === 'number') {
                    updates.push({ _id: item.id, 'system.value': aiValue });
                    console.log(`[AI-GM]   Match: "${item.name}" → ${path}.${matchKey} = ${aiValue}`);
                } else if (typeof aiValue === 'object' && aiValue !== null && 'value' in aiValue) {
                    updates.push({ _id: item.id, 'system.value': aiValue.value });
                    console.log(`[AI-GM]   Match: "${item.name}" → ${path}.${matchKey}.value = ${aiValue.value}`);
                }
                break; // matched – no need to check other maps
            }
        }

        if (updates.length > 0) {
            console.log(`[AI-GM] Heuristic sync: ${updates.length} AI skill values → embedded Items`);
            await actor.updateEmbeddedDocuments('Item', updates);
        } else {
            console.log('[AI-GM] No skill items could be matched to AI data');
        }
    }

    /**
     * Build item updates by comparing actor's embedded items against the reference
     * character's items. For each embedded item that matches a reference item by
     * name/type, copy over the numeric system fields from the AI-generated data
     * using the reference item's field structure as a guide.
     */
    private _buildReferenceBasedUpdates(
        actor: any,
        aiSystemData: Record<string, any>,
        refItems: Array<Record<string, any>>
    ): any[] {
        const updates: any[] = [];

        // Build a lookup: normalizedName → reference item
        const norm = (s: string): string =>
            s.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9]/g, '');

        const refLookup = new Map<string, Record<string, any>>();
        for (const refItem of refItems) {
            const name = refItem.name || '';
            refLookup.set(norm(name), refItem);
        }

        // Collect skill-like maps from AI data (for value lookup)
        const skillMaps = this._collectSkillDataMaps(aiSystemData);

        for (const item of actor.items) {
            const itemNorm = norm(item.name || '');
            const refItem = refLookup.get(itemNorm);
            if (!refItem) continue;

            // Inspect reference item's system data to find numeric field paths
            const refSystem = refItem.system;
            if (!refSystem || typeof refSystem !== 'object') continue;

            const update: Record<string, any> = { _id: item.id };
            let hasUpdate = false;

            // Walk reference item system fields and fill from AI data
            for (const [field, refValue] of Object.entries(refSystem)) {
                if (typeof refValue === 'number') {
                    // Try to find AI value for this item/skill
                    const aiVal = this._findAIValueForItem(item, field, skillMaps, aiSystemData);
                    if (aiVal !== null) {
                        update[`system.${field}`] = aiVal;
                        hasUpdate = true;
                        console.log(`[AI-GM]   Ref match: "${item.name}".system.${field} = ${aiVal}`);
                    }
                } else if (typeof refValue === 'object' && refValue !== null && 'value' in refValue && typeof refValue.value === 'number') {
                    const aiVal = this._findAIValueForItem(item, field, skillMaps, aiSystemData);
                    if (aiVal !== null) {
                        update[`system.${field}.value`] = aiVal;
                        hasUpdate = true;
                        console.log(`[AI-GM]   Ref match: "${item.name}".system.${field}.value = ${aiVal}`);
                    }
                }
            }

            if (hasUpdate) updates.push(update);
        }

        return updates;
    }

    /**
     * Try to find the AI-generated value for a specific item/skill.
     * Searches through skill maps and direct AI system data.
     */
    private _findAIValueForItem(
        item: any,
        _field: string,
        skillMaps: Array<{ path: string; map: Record<string, any> }>,
        _aiSystemData: Record<string, any>
    ): number | null {
        // Try to match item name to a key in any skill map
        for (const { map } of skillMaps) {
            const matchKey = this._fuzzyMatchItemToKey(item, Object.keys(map));
            if (matchKey !== null) {
                const val = map[matchKey];
                if (typeof val === 'number') return val;
                if (typeof val === 'object' && val !== null && 'value' in val && typeof val.value === 'number') {
                    return val.value;
                }
            }
        }
        return null;
    }

    /**
     * Align AI-generated items to reference character item structures.
     * For each AI item, if a reference item of the same type exists, merge
     * the reference's system structure as defaults (keeping AI's values on top).
     */
    private _alignItemsToReference(
        aiItems: Array<Record<string, any>>,
        refItems: Array<Record<string, any>>
    ): Array<Record<string, any>> {
        // Build type → first reference item lookup
        const refByType = new Map<string, Record<string, any>>();
        for (const ri of refItems) {
            const type = ri.type;
            if (type && !refByType.has(type)) refByType.set(type, ri);
        }

        return aiItems.map(aiItem => {
            const refTemplate = refByType.get(aiItem.type);
            if (!refTemplate) return aiItem;

            // Deep merge: reference system as base, AI system on top
            const mergedSystem = this._deepMerge(
                JSON.parse(JSON.stringify(refTemplate.system || {})),
                aiItem.system || {}
            );

            return { ...aiItem, system: mergedSystem };
        });
    }

    /**
     * Simple deep merge: target gets overwritten by source values.
     */
    private _deepMerge(target: any, source: any): any {
        if (!source || typeof source !== 'object') return source;
        if (!target || typeof target !== 'object') return source;
        const result = { ...target };
        for (const [key, val] of Object.entries(source)) {
            if (val && typeof val === 'object' && !Array.isArray(val) && typeof result[key] === 'object') {
                result[key] = this._deepMerge(result[key], val);
            } else {
                result[key] = val;
            }
        }
        return result;
    }

    /**
     * Walk the actor system data and collect any object that looks like
     * a skill/ability map (object with string keys → number values).
     */
    private _collectSkillDataMaps(system: any, path: string = ''): Array<{ path: string; map: Record<string, any> }> {
        const results: Array<{ path: string; map: Record<string, any> }> = [];
        if (!system || typeof system !== 'object') return results;

        // Known common keys that hold skill/ability maps
        const likelyCandidates = ['habilidades', 'skills', 'abilities', 'competences',
            'competencias', 'habilitats', 'pericias', 'capacidades'];

        for (const [key, value] of Object.entries(system)) {
            if (value && typeof value === 'object' && !Array.isArray(value)) {
                const fullPath = path ? `${path}.${key}` : key;
                // Check if this object looks like a skill map
                const entries = Object.entries(value as Record<string, any>);
                const numericCount = entries.filter(([, v]) =>
                    typeof v === 'number' || (typeof v === 'object' && v && 'value' in v)
                ).length;

                if (entries.length > 2 && numericCount >= entries.length * 0.6) {
                    results.push({ path: fullPath, map: value as Record<string, any> });
                }

                // Also check if it matches a known candidate name
                if (likelyCandidates.includes(key.toLowerCase()) && entries.length > 0) {
                    if (!results.find(r => r.path === fullPath)) {
                        results.push({ path: fullPath, map: value as Record<string, any> });
                    }
                }
            }
        }

        return results;
    }

    /**
     * Fuzzy-match a Foundry Item to a key in the AI's skill data.
     * Normalizes accents, whitespace, dots, and common prefixes.
     */
    private _fuzzyMatchItemToKey(item: any, keys: string[]): string | null {
        const norm = (s: string): string =>
            s.toLowerCase()
                .normalize('NFD').replace(/[\u0300-\u036f]/g, '')  // strip accents
                .replace(/[^a-z0-9]/g, '');                        // strip non-alphanumeric

        const itemName = norm(item.name || '');
        // Also try the item's internal identifier if available
        const itemId = norm(item.system?.identifier || item.flags?.core?.sourceId || '');

        for (const key of keys) {
            const nk = norm(key);
            if (!nk) continue;
            // Exact normalized match
            if (nk === itemName || nk === itemId) return key;
            // Substring match (e.g. item "F. Física" → "ffisica")
            if (itemName.includes(nk) || nk.includes(itemName)) return key;
            if (itemId && (itemId.includes(nk) || nk.includes(itemId))) return key;
        }

        return null;
    }

    /**
     * Structure-aware flatten: converts nested AI data into dot-notation paths,
     * using the actor's existing system data as a structural template.
     *
     * Key behavior: when the AI returns a flat primitive (e.g. habilidades.combate = 5)
     * but the actor's template expects a nested object with a `value` sub-field
     * (e.g. {label, value, min, max, ...}), this method routes the update to
     * `.value` instead of replacing the entire object.
     */
    private _flattenStructureAware(
        obj: Record<string, any>,
        prefix: string,
        result: Record<string, any>,
        template: any
    ): void {
        for (const [key, value] of Object.entries(obj)) {
            const path = prefix ? `${prefix}.${key}` : key;
            const tplValue = template?.[key];

            if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
                // AI returned a nested object → recurse into it
                this._flattenStructureAware(value, path, result, tplValue ?? {});
            } else if (
                // AI returned a primitive, but the template expects an object with .value
                (typeof value === 'number' || typeof value === 'string' || typeof value === 'boolean') &&
                tplValue && typeof tplValue === 'object' && !Array.isArray(tplValue) && 'value' in tplValue
            ) {
                result[`${path}.value`] = value;
                console.log(`[AI-GM] Structure fix: ${path} → ${path}.value = ${value}`);
            } else {
                result[path] = value;
            }
        }
    }

    private _onExportJSON(): void {
        if (!this.characterData) return;
        navigator.clipboard.writeText(JSON.stringify(this.characterData, null, 2));
        ui.notifications.info('Copied to clipboard');
    }

    private _onViewBlueprint(): void {
        const selectedArr = this.selectedFields.size > 0 ? Array.from(this.selectedFields) : null;
        const blueprint = game.aiGM.blueprintGenerator.generateAIBlueprint(this.selectedActorType, selectedArr);
        new Dialog({
            title: `Blueprint: ${this.selectedActorType}`,
            content: `<pre style="max-height:400px;overflow:auto;font-size:0.8rem;">${JSON.stringify(blueprint, null, 2)}</pre>`,
            buttons: {
                copy: {
                    label: 'Copy',
                    callback: () => {
                        navigator.clipboard.writeText(JSON.stringify(blueprint, null, 2));
                        ui.notifications.info('Copied');
                    }
                },
                close: { label: 'Close' }
            }
        }).render(true);
    }

    /* ================================================================== */
    /*  ITEMS TAB                                                          */
    /* ================================================================== */

    private async _onGenerateItems(_ev: any): Promise<void> {
        const html = this.element;
        const prompt: string = html.find('#gm-item-prompt').val()?.trim();
        const packId: string = html.find('#gm-item-pack').val();

        if (!prompt) return ui.notifications.warn('Enter an item description.');
        if (!packId) return ui.notifications.warn('Select a compendium pack.');

        const ws = game.aiGM?.wsClient;
        if (!ws?.isConnected()) return ui.notifications.error('WebSocket not connected.');

        const requestId = `${ws.getSessionId()}-${Date.now()}`;
        const ext = game.aiGM?.blueprintGenerator?.schemaExtractor;
        const validItemTypes = ext?.getItemTypes() ?? [];
        ws.generateItems(prompt, { packId, requestId, validItemTypes });
        ui.notifications.info('Item generation sent…');
    }

    /* ================================================================== */
    /*  LIBRARY TAB                                                        */
    /* ================================================================== */

    private async _onUploadBook(_ev: any): Promise<void> {
        const html = this.element;
        const fileInput = html.find('#gm-book-file')[0] as HTMLInputElement | undefined;
        const file = fileInput?.files?.[0];
        if (!file) return ui.notifications.warn('Select a PDF first.');
        if (file.type !== 'application/pdf') return ui.notifications.warn('Only PDF files.');

        const bookTitle: string = html.find('#gm-book-title').val()?.trim() || file.name;
        const sessionId: string = game.aiGM?.wsClient?.getSessionId() ?? `upload-${Date.now()}`;

        this._showProgress(html, 'ingest', 'Uploading…', 0);
        this._subscribeIngestionProgress(html, sessionId);

        const body = new FormData();
        body.append('file', file);
        body.append('worldId', game.world.id);
        body.append('foundrySystem', game.system.id);
        body.append('bookTitle', bookTitle);
        body.append('sessionId', sessionId);

        try {
            const res = await fetch(`${API}/api/books/upload`, { method: 'POST', body });
            if (!res.ok) throw new Error(`Server ${res.status}`);
            ui.notifications.info(`Ingestion started for "${bookTitle}".`);
        } catch (e: any) {
            ui.notifications.error(`Upload failed: ${e.message}`);
            this._hideProgress(html, 'ingest');
        }
    }

    private async _onDeleteBook(ev: any): Promise<void> {
        const bookId: string | undefined = ev.currentTarget.dataset.bookId;
        if (!bookId) return;
        const yes = await Dialog.confirm({ title: 'Delete Book', content: '<p>Remove this book and all indexed data?</p>' });
        if (!yes) return;
        try {
            await fetch(`${API}/api/books/${bookId}`, { method: 'DELETE' });
            ui.notifications.info('Book removed.');
            this.render(false);
        } catch (e: any) {
            ui.notifications.error(`Delete failed: ${e.message}`);
        }
    }

    private async _onIngestCompendium(_ev: any): Promise<void> {
        const html = this.element;
        const packId: string = html.find('#gm-knowledge-pack').val();
        if (!packId) return ui.notifications.warn('Select a compendium first.');

        const pack = game.packs?.get(packId);
        if (!pack) return ui.notifications.error('Compendium not found.');

        const sessionId: string = game.aiGM?.wsClient?.getSessionId() ?? `compendium-${Date.now()}`;
        this._showProgress(html, 'ingest', 'Reading compendium…', 0);
        this._subscribeIngestionProgress(html, sessionId);

        try {
            const documents = await pack.getDocuments();
            const entries = documents
                .map((doc: any) => this._serializeCompendiumDocument(doc))
                .filter((entry: any) => entry?.text);

            if (!entries.length) {
                this._hideProgress(html, 'ingest');
                return ui.notifications.warn('This compendium has no usable entries to ingest.');
            }

            const res = await fetch(`${API}/api/books/compendium`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    worldId: game.world.id,
                    foundrySystem: game.system.id,
                    packId: pack.collection,
                    packLabel: pack.metadata?.label ?? pack.title ?? pack.collection,
                    documentType: pack.documentName ?? 'Document',
                    sessionId,
                    entries
                })
            });
            if (!res.ok) throw new Error(`Server ${res.status}`);
            ui.notifications.info(`Compendium ingestion started for "${pack.metadata?.label ?? pack.collection}".`);
        } catch (e: any) {
            ui.notifications.error(`Compendium ingestion failed: ${e.message}`);
            this._hideProgress(html, 'ingest');
        }
    }

    private _subscribeIngestionProgress(html: any, _sessionId: string): void {
        const ws = game.aiGM?.wsClient;
        if (!ws) return;

        const handler = (event: any): void => {
            if (!event) return;
            this._showProgress(html, 'ingest', event.message ?? 'Processing…', event.progress ?? 0);
            if (event.status === 'COMPLETED' || event.status === 'FAILED') {
                setTimeout(() => {
                    this._hideProgress(html, 'ingest');
                    this.render(false);
                }, 1500);
            }
        };
        ws.on('onIngestionStarted', handler);
        ws.on('onIngestionProgress', handler);
        ws.on('onIngestionCompleted', handler);
        ws.on('onIngestionFailed', (event: any) => {
            this._showProgress(html, 'ingest', `Failed: ${event?.error ?? 'unknown'}`, 100);
            setTimeout(() => { this._hideProgress(html, 'ingest'); this.render(false); }, 3000);
        });
    }

    private async _refreshBooks(): Promise<void> {
        const worldId = game.world?.id;
        if (!worldId) { this.books = []; return; }
        try {
            const res = await fetch(`${API}/api/books/${worldId}`);
            this.books = res.ok ? await res.json() : [];
        } catch { this.books = []; }
    }

    private _serializeCompendiumDocument(doc: any): any | null {
        if (!doc) return null;

        const raw = typeof doc.toObject === 'function' ? doc.toObject() : doc;
        const type = raw.type ?? doc.documentName ?? doc.type ?? 'Document';
        const name = raw.name ?? doc.name ?? 'Unnamed';
        const documentName = doc.documentName ?? doc.constructor?.name ?? 'Document';
        const sections: string[] = [
            `Compendium entry: ${name}`,
            `Document class: ${documentName}`,
            `Type: ${type}`
        ];

        if (raw.system && Object.keys(raw.system).length) {
            sections.push(`System data:\n${JSON.stringify(raw.system, null, 2)}`);
        }

        if (Array.isArray(raw.items) && raw.items.length) {
            const itemSummary = raw.items.map((item: any) => ({
                name: item.name,
                type: item.type,
                system: item.system ?? {}
            }));
            sections.push(`Embedded items:\n${JSON.stringify(itemSummary, null, 2)}`);
        }

        if (Array.isArray(raw.pages) && raw.pages.length) {
            const pageContent = raw.pages
                .map((page: any) => {
                    const text = page.text?.content ?? page.src ?? page.name ?? '';
                    return text ? `Page: ${page.name ?? 'Unnamed'}\n${text}` : null;
                })
                .filter(Boolean)
                .join('\n\n');
            if (pageContent) sections.push(pageContent);
        }

        if (Array.isArray(raw.results) && raw.results.length) {
            const results = raw.results.map((result: any) => result.text ?? result.documentCollection ?? result.documentId).filter(Boolean);
            if (results.length) sections.push(`Table results:\n- ${results.join('\n- ')}`);
        }

        const description = raw.system?.description?.value
            ?? raw.system?.description
            ?? raw.description
            ?? raw.content
            ?? raw.biography
            ?? '';
        if (description) {
            const stripped = String(description).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
            if (stripped) sections.push(`Description:\n${stripped}`);
        }

        if (sections.length <= 3) {
            sections.push(`Raw data:\n${JSON.stringify(raw, null, 2)}`);
        }

        return {
            id: raw._id ?? doc.id ?? name,
            name,
            type,
            text: sections.join('\n\n'),
            metadata: {
                document_name: documentName
            }
        };
    }

    /* ================================================================== */
    /*  SESSION TAB                                                        */
    /* ================================================================== */

    private async _onAskAI(_ev: any): Promise<void> {
        const html = this.element;
        const message: string = html.find('#gm-session-prompt').val()?.trim();
        if (!message) return;

        html.find('#gm-session-prompt').val('');
        this.chatHistory.push({ sender: 'You', text: message });
        this._rerenderChat(html);

        const includeTokens = html.find('#gm-include-tokens').is(':checked');
        const token = includeTokens ? this._getPrimarySelectedToken() : null;
        const payload = {
            prompt: message,
            tokenId: token?.id ?? null,
            tokenName: token?.name ?? null,
            worldId: game.world.id,
            foundrySystem: game.system.id,
            conversationId: `${game.world.id}-session`,
            abilities: token ? this._collectTokenAbilities(token) : [],
            worldState: this._collectWorldState()
        };

        try {
            const res = await fetch(`${API}/gm/respond`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            if (!res.ok) throw new Error(`Server ${res.status}`);
            const data = await res.json();

            if (data.narration) {
                this.chatHistory.push({ sender: 'AI Game Master', text: data.narration });
                ChatMessage.create({ content: data.narration, speaker: { alias: 'AI Game Master' } });
            }
            // Execute VTT actions
            if (data.actions?.length) await this._executeActions(data.actions);

            this._rerenderChat(html);
        } catch (e: any) {
            this.chatHistory.push({ sender: 'System', text: `Error: ${e.message}` });
            this._rerenderChat(html);
        }
    }

    private _rerenderChat(html: any): void {
        const log = html.find('#gm-chat-log');
        if (this.chatHistory.length === 0) {
            log.html('<p class="empty-state">No messages yet.</p>');
            return;
        }
        log.html(this.chatHistory.map((m: ChatEntry) =>
            `<div class="chat-entry"><span class="chat-sender">${m.sender}</span><div class="chat-text">${m.text}</div></div>`
        ).join(''));
        log.scrollTop(log[0].scrollHeight);
    }

    private _refreshSelectedTokens(html: any): void {
        const container = html.find('#gm-selected-tokens');
        if (!container.length) return;

        const controlled = canvas.tokens?.controlled ?? [];
        if (controlled.length === 0) {
            container.html('<span class="empty-state">No tokens selected on canvas</span>');
            return;
        }

        const pills = controlled.map((t: any) => {
            const img = t.document?.texture?.src || t.actor?.img || 'icons/svg/mystery-man.svg';
            const name = this._escapeHtml(t.name || 'Unknown');
            return `<span class="token-pill"><img src="${img}" alt="${name}"> ${name}</span>`;
        });
        container.html(pills.join(''));
    }

    private _syncSelectedTokensFromCanvas(options: { onlyIfEmpty?: boolean; overwrite?: boolean } = {}): void {
        const { onlyIfEmpty = false, overwrite = true } = options;
        if (onlyIfEmpty && this.selectedTokenIds.size > 0) return;

        const controlled = canvas.tokens?.controlled ?? [];
        if (controlled.length === 0) return;

        const next = new Set<string>(controlled.map((t: any) => String(t.id)));
        if (overwrite) this.selectedTokenIds = next;
        else for (const id of next) this.selectedTokenIds.add(id);
    }

    private _getPrimarySelectedToken(): any | null {
        const placeables = canvas.tokens?.placeables ?? [];

        for (const id of this.selectedTokenIds) {
            const t = placeables.find((p: any) => p.id === id);
            if (t) return t;
        }

        return canvas.tokens?.controlled?.[0] ?? null;
    }

    private _onSceneTokenToggle(ev: any): void {
        const tokenId: string | undefined = ev.currentTarget?.dataset?.tokenId;
        if (!tokenId) return;
        if (ev.currentTarget.checked) this.selectedTokenIds.add(tokenId);
        else this.selectedTokenIds.delete(tokenId);
    }

    private _refreshSceneTokenList(html: any): void {
        const container = html.find('#gm-scene-tokens');
        if (!container.length) return;

        const placeables = canvas.tokens?.placeables ?? [];
        if (!placeables.length) {
            container.html('<span class="empty-state">No tokens in this scene</span>');
            return;
        }

        const tokens = placeables
            .filter((t: any) => t?.document)
            .sort((a: any, b: any) => String(a.name ?? '').localeCompare(String(b.name ?? '')));

        const rows = tokens.map((t: any) => {
            const img = t.document?.texture?.src || t.actor?.img || 'icons/svg/mystery-man.svg';
            const name = this._escapeHtml(t.name || 'Unknown');
            const checked = this.selectedTokenIds.has(t.id) ? 'checked' : '';
            return `
              <label class="scene-token-item" title="${name}">
                <input type="checkbox" class="scene-token-checkbox" data-token-id="${t.id}" ${checked}>
                <img src="${img}" alt="${name}">
                <span class="scene-token-name">${name}</span>
              </label>
            `;
        });

        container.html(rows.join(''));
    }

    private _escapeHtml(text: string): string {
        return String(text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    async close(options?: any): Promise<void> {
        if (this._controlTokenHookId !== null) {
            Hooks.off('controlToken', this._controlTokenHookId);
            this._controlTokenHookId = null;
        }
        return (Application.prototype as any).close.call(this, options);
    }

    /* ================================================================== */
    /*  SYSTEM TAB                                                         */
    /* ================================================================== */

    private async _onRelearnSystem(): Promise<void> {
        ui.notifications.info('Re-learning system…');
        try {
            const profile = await game.aiGM.snapshotSender.sendSnapshot(true);
            if (profile) game.aiGM.postProcessor.setProfile(profile);
            ui.notifications.info('System profile refreshed.');
            this.render(false);
        } catch (e: any) {
            ui.notifications.error(`Relearn failed: ${e.message}`);
        }
    }

    private async _onClearReference(): Promise<void> {
        const systemId = game.system?.id;
        if (!systemId) return;
        try {
            const res = await fetch(
                `${API}/gm/character/reference/${encodeURIComponent(systemId)}/${encodeURIComponent(this.selectedActorType)}`,
                { method: 'DELETE' }
            );
            if (!res.ok) throw new Error(`Server ${res.status}`);
            ui.notifications.info('Reference character cleared.');
            this.render(false);
        } catch (e: any) {
            ui.notifications.error(`Clear failed: ${e.message}`);
        }
    }

    private async _onSetReferenceCharacter(): Promise<void> {
        const actorId = String(this.element.find('#ai-gm-reference-actor').val() ?? '');
        if (!actorId) {
            ui.notifications.warn('Selecciona un actor primer.');
            return;
        }

        const actor = game.actors?.get(actorId);
        if (!actor) {
            ui.notifications.error('No s’ha trobat l’actor seleccionat.');
            return;
        }

        await this._storeReferenceCharacter(actor);
        this.render(false);
    }

    private async _storeReferenceCharacter(actor: any): Promise<void> {
        try {
            ui.notifications?.info(`Capturant "${actor.name}" com a reference character…`);

            const payload = {
                systemId: game.system.id,
                actorType: actor.type,
                label: actor.name,
                actorData: actor.toObject(),
                items: actor.items.map((item: any) => item.toObject())
            };

            const res = await fetch(`${API}/gm/character/reference`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            if (!res.ok) throw new Error(`Server ${res.status}`);

            ui.notifications?.info(
                `"${actor.name}" desat com a referència per ${actor.type} (${game.system.id}).`
            );
        } catch (e: any) {
            console.error('[AI-GM] Reference character capture failed:', e);
            ui.notifications?.error(`No s'ha pogut desar la referència: ${e.message}`);
        }
    }

    /**
     * Fetch the reference character for the current system + actor type.
     * Returns null if none is stored.
     */
    private async _fetchReferenceCharacter(): Promise<any | null> {
        try {
            const res = await fetch(
                `${API}/gm/character/reference/${encodeURIComponent(game.system.id)}/${encodeURIComponent(this.selectedActorType)}`
            );
            if (res.ok) return await res.json();
        } catch (_) { /* server unreachable */ }
        return null;
    }

    private async _onReconnectWS(): Promise<void> {
        try {
            await game.aiGM.wsClient.connect();
            ui.notifications.info('WebSocket reconnected.');
            // Re-subscribe to the active adventure queue after reconnection.
            if (this.adventureSessionId) {
                game.aiGM.wsClient.subscribeToAdventure(this.adventureSessionId);
            }
            this.render(false);
        } catch (e: any) {
            ui.notifications.error(`Reconnect failed: ${e.message}`);
        }
    }

    /* ================================================================== */
    /*  Shared helpers                                                     */
    /* ================================================================== */

    /** Resolves a server-relative URL (e.g. /audio/x.wav) to an absolute URL. */
    private _resolveAudioUrl(url: string | undefined): string | undefined {
        if (!url) return undefined;
        if (url.startsWith('http://') || url.startsWith('https://')) return url;
        return `${API}${url}`;
    }

    private _showProgress(html: any, prefix: string, message: string, percent: number): void {
        const container = html.find(`#${prefix}-progress`);
        container.addClass('active');
        container.find(`#${prefix}-progress-text`).text(message);
        container.find(`#${prefix}-progress-bar`).css('width', `${percent}%`);
    }

    private _hideProgress(html: any, prefix: string): void {
        html.find(`#${prefix}-progress`).removeClass('active');
    }

    /* ── Token / world state helpers ── */

    private _collectTokenAbilities(token: any): any[] {
        const actor = token.actor;
        if (!actor) return [];
        const abilities: any[] = [];

        for (const item of actor.items) {
            abilities.push({
                id: item.id, name: item.name, type: item.type,
                description: this._stripHtml(item.system?.description?.value || item.system?.description || ''),
                actionType: item.system?.actionType ?? null,
                damage: item.system?.damage?.parts?.map((p: any) => ({ formula: p[0], type: p[1] })) ?? [],
                range: item.system?.range ?? null,
                uses: item.system?.uses ? { value: item.system.uses.value, max: item.system.uses.max, per: item.system.uses.per } : null,
                level: item.system?.level ?? null
            });
        }
        if (actor.system?.abilities) {
            for (const [k, v] of Object.entries(actor.system.abilities)) {
                abilities.push({ id: `ability-${k}`, name: k.toUpperCase(), type: 'ability-score', value: (v as any).value, mod: (v as any).mod });
            }
        }
        if (actor.system?.skills) {
            for (const [k, v] of Object.entries(actor.system.skills)) {
                abilities.push({ id: `skill-${k}`, name: k, type: 'skill', value: (v as any).total ?? (v as any).value, proficient: (v as any).proficient });
            }
        }
        return abilities;
    }

    private _collectWorldState(): any {
        const scene = game.scenes?.active;
        return {
            sceneName: scene?.name ?? null,
            sceneId: scene?.id ?? null,
            tokens: scene ? canvas.tokens.placeables.map((t: any) => ({
                id: t.id, name: t.name, x: t.x, y: t.y,
                actorId: t.actor?.id ?? null,
                hp: t.actor?.system?.attributes?.hp ? { value: t.actor.system.attributes.hp.value, max: t.actor.system.attributes.hp.max } : null,
                disposition: t.document?.disposition
            })) : [],
            combat: game.combat ? { round: game.combat.round, turn: game.combat.turn, currentCombatantId: game.combat.combatant?.tokenId } : null,
            lastRoll: this.lastAdventureRoll
        };
    }

    private _stripHtml(html: string): string {
        if (!html) return '';
        const tmp = document.createElement('DIV');
        tmp.innerHTML = html;
        return tmp.textContent || tmp.innerText || '';
    }

    public async handleAdventureChatMessage(message: any): Promise<void> {
        if (!this.adventureSessionId || !message) return;

        const rollInfo = this._extractAdventureRollInfo(message);
        if (!rollInfo) return;
        if (rollInfo.messageId && this.processedAdventureRollMessageIds.has(rollInfo.messageId)) return;
        if (rollInfo.messageId) this.processedAdventureRollMessageIds.add(rollInfo.messageId);

        this.lastAdventureRoll = rollInfo;

        const summary = this._formatAdventureRollSummary(rollInfo);
        this.adventureLog.push(`🎲 ${summary}`);
        this._rebuildAdventureSessionSummary();

        const ws = game.aiGM?.wsClient;
        if (!ws || !this.adventureSessionId) {
            this.render(false);
            return;
        }

        const context = await this._promptAdventureRollContext(rollInfo);
        if (context === null) {
            this.adventureLog.push('⏸️ Interpretació de la tirada ajornada.');
            this._rebuildAdventureSessionSummary();
            this.render(false);
            return;
        }

        const contextInstruction = context.trim()
            ? ` Context addicional del jugador: ${context.trim()}.`
            : '';

        ws.sendTranscription({
            transcription: `[RESULTAT DE TIRADA] ${summary}.${contextInstruction} Resol la situació immediata segons el resultat de la tirada i les regles aplicables; no demanis una nova tirada si aquesta ja resol l'acció.`,
            adventureSessionId: this.adventureSessionId,
            worldId: game.world?.id ?? undefined,
            foundrySystem: game.system?.id ?? undefined,
            playerName: rollInfo.actorName ?? rollInfo.speakerAlias ?? game.user?.name ?? undefined,
            worldState: this._collectWorldState()
        });
        this.isProcessing = true;
        this.render(false);
    }

    private _extractAdventureRollInfo(message: any): AdventureRollInfo | null {
        const roll = Array.isArray(message?.rolls) && message.rolls.length > 0 ? message.rolls[0] : null;
        const isRoll = !!roll || !!message?.isRoll;
        if (!isRoll) return null;

        const actor = message?.actor ?? game.actors?.get?.(message?.speaker?.actor);
        const flavor = this._stripHtml(message?.flavor ?? '');
        const content = this._stripHtml(message?.content ?? '');
        const outcome = this._inferRollOutcome(message, content, flavor);
        const target = this._inferRollTarget(message, content, flavor);
        const total = typeof roll?.total === 'number' ? roll.total : null;
        const success = outcome === 'success' || outcome === 'critical-success'
            ? true
            : outcome === 'failure' || outcome === 'critical-failure'
                ? false
                : null;
        const margin = target != null && total != null ? total - target : null;

        return {
            messageId: message?.id ?? undefined,
            actorId: actor?.id ?? message?.speaker?.actor ?? undefined,
            actorName: actor?.name ?? undefined,
            speakerAlias: message?.speaker?.alias ?? undefined,
            tokenId: message?.speaker?.token ?? undefined,
            flavor: flavor || undefined,
            formula: roll?.formula ?? undefined,
            total: total ?? undefined,
            target,
            margin,
            outcome,
            success,
            content: content || undefined,
            dice: Array.isArray(roll?.dice)
                ? roll.dice.map((die: any) => ({
                    faces: die?.faces,
                    results: Array.isArray(die?.results)
                        ? die.results.map((result: any) => result?.result).filter((value: any) => typeof value === 'number')
                        : []
                }))
                : [],
            rolledAt: Date.now()
        };
    }

    private _inferRollOutcome(message: any, content: string, flavor: string): string | null {
        const candidates = [
            message?.flags?.dnd5e?.roll?.outcome,
            message?.flags?.system?.roll?.outcome,
            message?.flags?.coc7?.result?.outcome,
            message?.flags?.coc7?.check?.successLevel,
            content,
            flavor
        ].filter((value): value is string => typeof value === 'string' && value.trim().length > 0);

        for (const raw of candidates) {
            const text = raw.toLowerCase();
            if (/(critical success|èxit crític|exito crítico|extreme success|hard success)/.test(text)) return 'critical-success';
            if (/(critical failure|fumble|pifia|fracàs crític|fracaso crítico)/.test(text)) return 'critical-failure';
            if (/(success|èxit|exito|passed|supera)/.test(text)) return 'success';
            if (/(failure|failed|fracàs|fracaso|falla|miss)/.test(text)) return 'failure';
        }
        return null;
    }

    private _buildImplicitReferenceCharacter(): any | null {
        const actors = (Array.from(game.actors ?? []) as any[])
            .filter((actor: any) => actor?.type === this.selectedActorType)
            .sort((a: any, b: any) => {
                const itemDiff = (b?.items?.size ?? 0) - (a?.items?.size ?? 0);
                if (itemDiff !== 0) return itemDiff;
                const fieldDiff = Object.keys(b?.system ?? {}).length - Object.keys(a?.system ?? {}).length;
                if (fieldDiff !== 0) return fieldDiff;
                return String(a?.name ?? '').localeCompare(String(b?.name ?? ''));
            });

        const candidate: any = actors.find((actor: any) => (actor?.items?.size ?? 0) > 0 || Object.keys(actor?.system ?? {}).length > 10);
        if (!candidate) return null;

        try {
            return {
                systemId: game.system.id,
                actorType: candidate.type,
                label: candidate.name,
                actorData: candidate.toObject(),
                items: candidate.items.map((item: any) => item.toObject()),
                capturedAt: Date.now()
            };
        } catch (e) {
            console.warn('[AI-GM] Failed to build implicit reference character:', e);
            return null;
        }
    }

    private _inferRollTarget(message: any, content: string, flavor: string): number | null {
        const explicitCandidates = [
            message?.flags?.dnd5e?.roll?.targetValue,
            message?.flags?.system?.roll?.targetValue,
            message?.flags?.coc7?.check?.difficulty,
            message?.flags?.coc7?.check?.rawValue
        ];
        for (const candidate of explicitCandidates) {
            if (typeof candidate === 'number' && Number.isFinite(candidate)) return candidate;
        }

        for (const text of [content, flavor]) {
            const match = text.match(/(?:vs\.?|contra|target|dificultat|difficulty|dc)\s*(\d{1,3})/i);
            if (match) return Number(match[1]);
        }
        return null;
    }

    private _formatAdventureRollSummary(roll: AdventureRollInfo): string {
        const who = roll.actorName ?? roll.speakerAlias ?? 'Algú';
        const what = roll.flavor || roll.formula || 'tirada';
        const total = typeof roll.total === 'number' ? ` = ${roll.total}` : '';
        const target = typeof roll.target === 'number' ? ` vs ${roll.target}` : '';
        const outcome = roll.outcome
            ? ({
                'critical-success': 'èxit crític',
                'critical-failure': 'fracàs crític',
                success: 'èxit',
                failure: 'fracàs'
            } as Record<string, string>)[roll.outcome] ?? roll.outcome
            : (typeof roll.success === 'boolean' ? (roll.success ? 'èxit' : 'fracàs') : null);
        return `${who} ha fet ${what}${total}${target}${outcome ? ` (${outcome})` : ''}`;
    }

    private _promptAdventureRollContext(roll: AdventureRollInfo): Promise<string | null> {
        const summary = this._formatAdventureRollSummary(roll);

        return new Promise((resolve) => {
            let settled = false;
            const finish = (value: string | null): void => {
                if (settled) return;
                settled = true;
                resolve(value);
            };

            const dialog = new Dialog({
                title: 'Interpretar tirada',
                content: `
                    <p>${this._escapeHtml(summary)}</p>
                    <p class="hint">Vols afegir més context perquè el Director interpreti millor aquesta tirada?</p>
                    <div class="form-group">
                      <label for="ai-gm-roll-context">Context opcional</label>
                      <textarea id="ai-gm-roll-context" rows="4" placeholder="Ex.: què volia aconseguir el jugador, contra què feia la tirada, conseqüències esperades..."></textarea>
                    </div>
                `,
                buttons: {
                    send: {
                        icon: '<i class="fas fa-paper-plane"></i>',
                        label: 'Enviar sense context',
                        callback: () => finish('')
                    },
                    sendWithContext: {
                        icon: '<i class="fas fa-comment-dots"></i>',
                        label: 'Afegir context i enviar',
                        callback: (html: any) => {
                            const text = String(html.find('#ai-gm-roll-context').val() ?? '').trim();
                            finish(text);
                        }
                    },
                    ignore: {
                        icon: '<i class="fas fa-pause"></i>',
                        label: 'Ara no',
                        callback: () => finish(null)
                    }
                },
                default: 'sendWithContext',
                close: () => finish(null)
            });
            dialog.render(true);
        });
    }

    private async _executeActions(actions: VTTAction[]): Promise<void> {
        for (const action of actions) {
            try {
                switch (action.type) {
                    case 'createToken': {
                        const scene = game.scenes.active;
                        await scene.createEmbeddedDocuments('Token', [{ name: action.name, img: action.img, x: action.x, y: action.y, actorId: action.actorId }]);
                        break;
                    }
                    case 'applyDamage': {
                        const actor = game.actors.get(action.target);
                        if (actor) await actor.applyDamage(action.amount);
                        break;
                    }
                    case 'moveToken': {
                        const tok = canvas.tokens.get(action.tokenId);
                        if (tok) await tok.document.update({ x: action.x, y: action.y });
                        break;
                    }
                    case 'useAbility': {
                        const tok = canvas.tokens.get(action.tokenId);
                        const item = tok?.actor?.items.get(action.abilityId);
                        if (item) await item.use({}, { event: null });
                        break;
                    }
                    case 'rollAbilityCheck': {
                        const tok = canvas.tokens.get(action.tokenId);
                        if (tok?.actor) await tok.actor.rollAbilityTest(action.ability);
                        break;
                    }
                    case 'rollSkillCheck': {
                        const tok = canvas.tokens.get(action.tokenId);
                        if (tok?.actor) await tok.actor.rollSkill(action.skill);
                        break;
                    }
                    case 'rollSavingThrow': {
                        const tok = canvas.tokens.get(action.tokenId);
                        if (tok?.actor) await tok.actor.rollAbilitySave(action.ability);
                        break;
                    }
                }
            } catch (e) {
                console.error(`[AI-GM] Action ${action.type} failed:`, e);
            }
        }
    }

    /* ================================================================== */
    /*  SKILL MANAGEMENT                                                   */
    /* ================================================================== */

    private async _onImportSkill(_ev: any): Promise<void> {
        const registry = game.aiGM?.skillRegistry;
        if (!registry) {
            ui.notifications.error('Skill registry not available');
            return;
        }

        // Use a hidden file input to let the user pick a JSON file
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = '.json';
        input.onchange = async () => {
            const file = input.files?.[0];
            if (!file) return;
            try {
                const text = await file.text();
                const count = registry.importFromJSON(text);
                ui.notifications.info(`Imported ${count} skill(s)`);
                this.render(false);
            } catch (e: any) {
                ui.notifications.error(`Import failed: ${e.message}`);
            }
        };
        input.click();
    }

    private _onCreateSkill(_ev: any): void {
        const registry = game.aiGM?.skillRegistry;
        if (!registry) return;

        const template: SystemSkill = {
            id: '',
            name: 'My Custom Skill',
            description: 'Describe what this skill does',
            systemId: game.system.id,
            worldId: game.world?.id ?? '',
            priority: 0,
            enabled: true,
            extraActorTypes: [],
            actorOverrides: {},
            constraints: [],
            creationHints: '',
            creationSteps: [],
            defaultItems: [],
            fieldAliases: {}
        };

        this._openSkillEditor(template, (skill) => {
            registry.upsert(skill);
            ui.notifications.info(`Skill "${skill.name}" created`);
            this.render(false);
        });
    }

    private _onExportSkills(_ev: any): void {
        const registry = game.aiGM?.skillRegistry;
        if (!registry) return;

        const json = registry.exportToJSON();
        const blob = new Blob([json], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `ai-gm-skills-${game.system.id}.json`;
        a.click();
        URL.revokeObjectURL(url);
    }

    private _onToggleSkill(ev: any): void {
        const registry = game.aiGM?.skillRegistry;
        if (!registry) return;

        const skillId = ev.currentTarget?.dataset?.skillId;
        if (skillId && registry.toggleEnabled(skillId)) {
            this.render(false);
        }
    }

    private _onEditSkill(ev: any): void {
        const registry = game.aiGM?.skillRegistry;
        if (!registry) return;

        const skillId = ev.currentTarget?.dataset?.skillId;
        const skill = registry.getAllSkills().find((s: SystemSkill) => s.id === skillId);
        if (!skill) return;

        this._openSkillEditor(skill, (updated) => {
            registry.upsert(updated);
            ui.notifications.info(`Skill "${updated.name}" updated`);
            this.render(false);
        });
    }

    private async _onDeleteSkill(ev: any): Promise<void> {
        const registry = game.aiGM?.skillRegistry;
        if (!registry) return;

        const skillId = ev.currentTarget?.dataset?.skillId;
        if (!skillId) return;

        const confirmed = await Dialog.confirm({
            title: 'Delete Skill',
            content: '<p>Are you sure you want to delete this skill?</p>'
        });

        if (confirmed) {
            registry.remove(skillId);
            ui.notifications.info('Skill deleted');
            this.render(false);
        }
    }

    /**
     * Open a dialog with a JSON editor for the skill.
     */
    private _openSkillEditor(skill: SystemSkill, onSave: (s: SystemSkill) => void): void {
        const json = JSON.stringify(skill, null, 2);

        const d = new Dialog({
            title: skill.id ? `Edit Skill: ${skill.name}` : 'New Skill',
            content: `
                <div style="margin-bottom:8px;">
                    <p class="hint">Edit the skill JSON below. Required fields: <code>name</code>, <code>systemId</code>.</p>
                </div>
                <textarea id="skill-json-editor" style="width:100%;height:350px;font-family:monospace;font-size:0.85rem;tab-size:2;">${this._escapeHtml(json)}</textarea>
            `,
            buttons: {
                save: {
                    icon: '<i class="fas fa-save"></i>',
                    label: 'Save',
                    callback: (html: any) => {
                        const raw = html.find('#skill-json-editor').val() as string;
                        try {
                            const parsed = JSON.parse(raw) as SystemSkill;
                            if (!parsed.name || !parsed.systemId) {
                                ui.notifications.error('Skill must have a "name" and "systemId"');
                                return;
                            }
                            onSave(parsed);
                        } catch (e: any) {
                            ui.notifications.error(`Invalid JSON: ${e.message}`);
                        }
                    }
                },
                cancel: {
                    icon: '<i class="fas fa-times"></i>',
                    label: 'Cancel'
                }
            },
            default: 'save'
        }, { width: 560, height: 500 });

        d.render(true);
    }

    /* ================================================================== */
    /*  ADVENTURE TAB                                                      */
    /* ================================================================== */

    private async _refreshAdventures(): Promise<void> {
        const worldId = game.world?.id;
        if (!worldId) {
            this.adventures = [];
            return;
        }
        try {
            const res = await fetch(`${API}/adventure/${encodeURIComponent(worldId)}`);
            if (res.ok) {
                const list = await res.json();
                this.adventures = (list || []) as AdventureSummary[];
                if (this.selectedAdventureId && !this.adventures.find(a => a.id === this.selectedAdventureId)) {
                    this.selectedAdventureId = null;
                }
            } else {
                this.adventures = [];
            }
        } catch (_e) {
            // Server unreachable; leave list empty.
            this.adventures = [];
        }
    }

    private async _refreshAdventureSessions(): Promise<void> {
        const worldId = game.world?.id;
        if (!worldId || !this.selectedAdventureId) {
            this.adventureSavedSessions = [];
            return;
        }
        try {
            const res = await fetch(
                `${API}/adventure/${encodeURIComponent(this.selectedAdventureId)}/sessions?worldId=${encodeURIComponent(worldId)}`
            );
            if (res.ok) {
                this.adventureSavedSessions = (await res.json() ?? []) as AdventureSavedSessionSummary[];
            } else {
                this.adventureSavedSessions = [];
            }
        } catch (_e) {
            this.adventureSavedSessions = [];
        }
    }

    private _onAdventureSelectChange(ev: any): void {
        this.selectedAdventureId = ev.target?.value || null;
        this.render(false);
    }

    private async _onUploadAdventure(_ev: any): Promise<void> {
        const worldId = game.world?.id;
        if (!worldId) {
            ui.notifications.warn('No active world');
            return;
        }

        const input = document.createElement('input');
        input.type = 'file';
        input.accept = 'application/pdf';
        input.style.display = 'none';
        document.body.appendChild(input);

        const cleanup = () => input.remove();

        input.onchange = async () => {
            const file = input.files?.[0];
            cleanup();
            if (!file) return;
            const fd = new FormData();
            fd.append('file', file);
            fd.append('foundrySystem', game.system?.id ?? '');
            fd.append('worldId', worldId);
            fd.append('bookTitle', file.name.replace(/\.pdf$/i, ''));

            ui.notifications.info(`Ingerint aventura "${file.name}"...`);
            try {
                const res = await fetch(`${API}/adventure/upload`, { method: 'POST', body: fd });
                if (!res.ok) {
                    const errText = await res.text();
                    ui.notifications.error(`Error ingerint aventura: ${errText}`);
                    return;
                }
                const adventure = await res.json() as AdventureSummary;
                ui.notifications.info(`Aventura "${adventure.title}" carregada`);
                await this._refreshAdventures();
                this.selectedAdventureId = adventure.id;
                this.render(false);
            } catch (e: any) {
                ui.notifications.error(`Error pujant aventura: ${e?.message ?? e}`);
            }
        };
        // Handle cancel (user closes file dialog without selecting)
        input.addEventListener('cancel', cleanup);
        input.click();
    }

    private async _onStartAdventure(_ev: any): Promise<void> {
        if (!this.selectedAdventureId) {
            ui.notifications.warn('Selecciona una aventura primer');
            return;
        }
        try {
            const worldId = game.world?.id;
            const startUrl = `${API}/adventure/${encodeURIComponent(this.selectedAdventureId)}/start`
                + (worldId ? `?worldId=${encodeURIComponent(worldId)}` : '');
            const res = await fetch(startUrl, { method: 'POST' });
            if (!res.ok) {
                ui.notifications.error(`No s'ha pogut iniciar la sessió: ${await res.text()}`);
                return;
            }
            const data = await res.json() as AdventureStartResponse;
            this._loadAdventureSessionState(data);

            ui.notifications.info('Aventura iniciada!');
            this.render(false);

            // Send an initial message to the Director to trigger the opening narration
            setTimeout(() => {
                game.aiGM?.wsClient?.sendTranscription({
                    transcription: '[Inici de l\'aventura — narra la introducció de l\'escena actual]',
                    adventureSessionId: data.sessionId,
                    worldId: worldId ?? undefined,
                    foundrySystem: game.system?.id ?? undefined,
                    playerName: game.user?.name ?? undefined
                });
                this.isProcessing = true;
                this.render(false);
            }, 500);
        } catch (e: any) {
            ui.notifications.error(`Error iniciant l'aventura: ${e?.message ?? e}`);
        }
    }

    private async _onResumeAdventure(ev: any): Promise<void> {
        const sessionId = ev.currentTarget?.dataset?.sessionId;
        if (!sessionId) return;
        try {
            const res = await fetch(`${API}/adventure/session/${encodeURIComponent(sessionId)}/resume`, { method: 'POST' });
            if (!res.ok) {
                ui.notifications.error(`No s'ha pogut reprendre la sessió: ${await res.text()}`);
                return;
            }
            const data = await res.json() as AdventureStartResponse;
            this._loadAdventureSessionState(data);
            this.adventureLog = data.sessionSummary
                ? [`Resum de la sessió: ${data.sessionSummary}`]
                : ['Sessió represa.'];
            ui.notifications.info('Sessió represa!');
            this.render(false);
        } catch (e: any) {
            ui.notifications.error(`Error reprenent la sessió: ${e?.message ?? e}`);
        }
    }

    private _onEndAdventure(_ev: any): void {
        this._audioCapture?.cancel();
        this._audioPlayback?.stop();
        this._resetAdventureState();
        this.render(false);
    }

    private _loadAdventureSessionState(data: AdventureStartResponse): void {
        this.adventureSessionId = data.sessionId;
        this.adventureSessionName = data.sessionName ?? null;
        this.adventureSessionParticipants = Array.isArray(data.participantNames) ? [...data.participantNames] : [];
        this.adventureSessionSummary = data.sessionSummary ?? null;
        this.adventureScene = data.currentScene ?? null;
        this.discoveredClues = Array.isArray(data.discoveredClues) ? [...data.discoveredClues] : [];
        this.metNpcs = Array.isArray(data.metNpcs) ? [...data.metNpcs] : [];
        this.tensionLevel = typeof data.tensionLevel === 'number' ? data.tensionLevel : 0;
        this.speakingNpc = null;
        this.isRecording = false;
        this.isProcessing = false;

        const adventureId = data.adventureId ?? this.selectedAdventureId;
        if (adventureId) this.selectedAdventureId = adventureId;

        const adv = this.adventures.find(a => a.id === adventureId);
        this.adventureNpcs = (adv?.npcs ?? []) as AdventureNpcInfo[];
        this.adventureLog = [];
        this.lastAdventureRoll = null;
        this.processedAdventureRollMessageIds.clear();
        this._rebuildAdventureSessionSummary();

        game.aiGM?.wsClient?.subscribeToAdventure(data.sessionId);
        this._initAdventureAudio();
    }

    private _resetAdventureState(): void {
        this.adventureSessionId = null;
        this.adventureSessionName = null;
        this.adventureSessionParticipants = [];
        this.adventureSessionSummary = null;
        this.adventureScene = null;
        this.discoveredClues = [];
        this.metNpcs = [];
        this.tensionLevel = 0;
        this.adventureNpcs = [];
        this.adventureLog = [];
        this.lastAdventureRoll = null;
        this.processedAdventureRollMessageIds.clear();
        this.speakingNpc = null;
        this.isRecording = false;
        this.isProcessing = false;
    }

    private _onSendAdventureText(_ev: any): void {
        if (!this.adventureSessionId) {
            ui.notifications.warn('Inicia una sessió d\'aventura primer');
            return;
        }
        const inputEl = document.getElementById('adventure-text-input') as HTMLInputElement | null;
        const text = inputEl?.value?.trim();
        if (!text) return;
        inputEl!.value = '';

        const ws = game.aiGM?.wsClient;
        if (!ws) { ui.notifications.error('WebSocket no connectat'); return; }

        ws.sendTranscription({
            transcription: text,
            adventureSessionId: this.adventureSessionId,
            worldId: game.world?.id ?? undefined,
            foundrySystem: game.system?.id ?? undefined,
            playerName: game.user?.name ?? undefined,
            worldState: this._collectWorldState()
        });
        this.isProcessing = true;
        this.render(false);
    }

    private async _onCreateAdventureNpc(ev: any): Promise<void> {
        const npcId = ev.currentTarget?.dataset?.npcId;
        const npc = this.adventureNpcs.find(n => n.id === npcId);
        if (!npc) return;

        // Determine the correct NPC actor type using schema extractor
        const ext = game.aiGM?.blueprintGenerator?.schemaExtractor;
        const actorTypes: string[] = ext?.getActorTypes() ?? ['character'];
        const actorType = actorTypes.includes('npc') ? 'npc' : actorTypes[0];

        // Build descriptive prompt from NPC adventure data
        const promptParts = [`Crea un NPC anomenat "${npc.name}" per a una aventura de rol.`];
        if (npc.personality) promptParts.push(`Personalitat: ${npc.personality}`);
        if (npc.objectives) promptParts.push(`Objectius: ${npc.objectives}`);
        if (npc.currentDisposition) promptParts.push(`Disposició actual: ${npc.currentDisposition}`);
        const prompt = promptParts.join('\n');

        ui.notifications.info(`Generant NPC "${npc.name}"…`);

        try {
            // Generate a full AI blueprint for the NPC actor type
            let blueprint = game.aiGM.blueprintGenerator.generateAIBlueprint(actorType);
            if (game.aiGM.postProcessor) blueprint = game.aiGM.postProcessor.enhanceBlueprint(blueprint);

            const sessionId: string | null = game.aiGM?.wsClient?.getSessionId() ?? null;
            const request = this._charService.buildRequest({ prompt, actorType, blueprint, language: 'ca', sessionId });
            const charData = await this._charService.generateCharacter(request);
            const sanitized = CharacterDataSanitizer.sanitize(charData);
            if (!sanitized) throw new Error('Character generation returned empty data');

            const actorName = sanitized.actor?.name || npc.name;
            const aiSystemData = sanitized.actor?.system ?? {};

            // Phase 1: Create minimal actor (lets system add defaults)
            const minimalActor = await (Actor as any).create({
                name: actorName,
                type: actorType,
                img: sanitized.actor?.img || 'icons/svg/mystery-man.svg'
            });
            if (!minimalActor) throw new Error('Actor creation failed');

            // Phase 2: Update with AI-generated system data
            if (Object.keys(aiSystemData).length > 0) {
                const existingSystem = (minimalActor as any).system ?? {};
                const flatUpdate: Record<string, any> = {};
                this._flattenStructureAware(aiSystemData, 'system', flatUpdate, existingSystem);
                await minimalActor.update(flatUpdate);
            }

            // Phase 3: Sync AI skill values to embedded items
            if (minimalActor.items.size > 0) {
                await this._syncAISkillsToItems(minimalActor, aiSystemData, null);
            }

            // Phase 4: Add AI-generated equipment/items
            if (sanitized.items?.length) {
                await this._upsertGeneratedItems(minimalActor, sanitized.items);
            }

            ui.notifications.info(`NPC "${npc.name}" creat com a actor!`);
            minimalActor.sheet.render(true);
        } catch (e: any) {
            console.error('[AI-GM] Adventure NPC creation failed:', e);
            ui.notifications.error(`Error creant NPC: ${e?.message ?? e}`);
        }
    }

    private async _onToggleMic(_ev: any): Promise<void> {
        if (!this.adventureSessionId) {
            ui.notifications.warn('Inicia una sessió d\'aventura primer');
            return;
        }
        if (!this._audioCapture) this._initAdventureAudio();
        try {
            await this._audioCapture?.toggle();
        } catch (e: any) {
            ui.notifications.error(`Microphone error: ${e?.message ?? e}`);
        }
    }

    private _initAdventureAudio(): void {
        if (!this.adventureSessionId) return;
        const ws = game.aiGM?.wsClient;
        if (!ws) return;

        this._audioCapture = new AudioCaptureService(ws, {
            adventureSessionId: this.adventureSessionId,
            worldId: game.world?.id ?? undefined,
            foundrySystem: game.system?.id ?? undefined,
            playerName: game.user?.name ?? undefined,
            worldState: () => this._collectWorldState(),
            onStateChange: (recording) => {
                this.isRecording = recording;
                this.render(false);
            },
            onSubmitting: () => {
                this.isProcessing = true;
                this.render(false);
            }
        });

        this._audioPlayback = new AudioPlaybackService({
            onSpeakingChange: (npc: NpcDialoguePayload | null) => {
                this.speakingNpc = npc;
                this.render(false);
            }
        });
    }

    private _registerAdventureWsHandlers(): void {
        if (this._adventureHandlersRegistered) return;
        const ws = game.aiGM?.wsClient;
        if (!ws) return;

        // Re-subscribe to the adventure queue after any reconnection.
        ws.on('onConnected', (_data: any) => {
            if (this.adventureSessionId) {
                ws.subscribeToAdventure(this.adventureSessionId);
            }
        });

        ws.on('onTranscriptionReceived', (data: any) => {
            this.isProcessing = false;
            if (data?.transcription) {
                this.adventureLog.push(`Tu: ${data.transcription}`);
                this._rememberAdventureParticipant(game.user?.name ?? null);
                this._rebuildAdventureSessionSummary();
                this.render(false);
            }
        });

        ws.on('onDirectorNarration', (data: DirectorNarrationPayload) => {
            this.isProcessing = false;
            if (data?.narration) {
                this.adventureLog.push(`Director: ${data.narration}`);
                // Post to Foundry main chat
                try {
                    (ChatMessage as any).create({
                        content: `<div class="ai-gm-narration" style="font-style:italic;">${data.narration}</div>`,
                        speaker: { alias: 'Director' }
                    });
                } catch (_e) { /* best effort */ }
                // Play narration audio if available (URL preferred, base64 as fallback)
                const narrationAudioSrc = data.narrationAudioUrl || data.narrationAudioBase64;
                if (narrationAudioSrc && this._audioPlayback) {
                    this._audioPlayback.enqueue({
                        npcId: 'director',
                        npcName: 'Director',
                        text: data.narration,
                        audioUrl: this._resolveAudioUrl(data.narrationAudioUrl),
                        audioBase64: data.narrationAudioBase64
                    });
                }
                this._rebuildAdventureSessionSummary();
                this.render(false);
            }
            // Apply VTT actions if any
            if (Array.isArray(data?.actions) && data.actions.length) {
                this._applyVttActions(data.actions);
            }
        });

        ws.on('onDirectorAudioReady', (data: DirectorNarrationPayload) => {
            if (data?.narrationAudioUrl && this._audioPlayback) {
                this._audioPlayback.enqueue({
                    npcId: 'director',
                    npcName: 'Director',
                    text: data.narration,
                    audioUrl: this._resolveAudioUrl(data.narrationAudioUrl)
                });
            }
        });

        ws.on('onNpcDialogueAudio', (data: NpcDialoguePayload) => {
            const npcLabel = data?.npcName ?? data?.npcId ?? 'NPC';
            if (data?.text) {
                this.adventureLog.push(`${npcLabel}: ${data.text}`);
                // Post to Foundry main chat
                try {
                    (ChatMessage as any).create({
                        content: `<strong>${this._escapeHtml(npcLabel)}</strong>: ${this._escapeHtml(data.text)}`,
                        speaker: { alias: npcLabel }
                    });
                } catch (_e) { /* best effort */ }
            }
            this._rebuildAdventureSessionSummary();
            if (data?.audioUrl || data?.audioBase64) {
                this._audioPlayback?.enqueue({ ...data, audioUrl: this._resolveAudioUrl(data.audioUrl) });
            } else {
                this.render(false);
            }
        });

        ws.on('onNpcAudioReady', (data: NpcDialoguePayload) => {
            if (data?.audioUrl || data?.audioBase64) {
                this._audioPlayback?.enqueue({ ...data, audioUrl: this._resolveAudioUrl(data.audioUrl) });
            }
        });

        ws.on('onIntentConfirmationRequest', (data: IntentConfirmationPayload) => {
            this.isProcessing = false;
            this._showIntentConfirmationDialog(data);
        });

        ws.on('onSceneTransition', (data: any) => {
            if (data?.title) this.adventureScene = { title: data.title, readAloudText: data.readAloudText ?? '' };
            this.adventureLog.push(`— Transició: ${data?.title ?? data?.sceneId ?? '?'} —`);
            this._rebuildAdventureSessionSummary();
            this.render(false);
        });

        ws.on('onAdventureStateUpdate', (data: AdventureStateUpdatePayload) => {
            if (Array.isArray(data?.discoveredClues)) {
                for (const c of data.discoveredClues) {
                    if (!this.discoveredClues.includes(c)) this.discoveredClues.push(c);
                }
            }
            if (data?.npcDispositionChanges) {
                for (const npcId of Object.keys(data.npcDispositionChanges)) {
                    if (!this.metNpcs.includes(npcId)) {
                        const npc = this.adventureNpcs.find(n => n.id === npcId);
                        this.metNpcs.push(npc?.name ?? npcId);
                    }
                }
            }
            if (typeof data?.tensionDelta === 'number') {
                this.tensionLevel = Math.max(0, Math.min(10, this.tensionLevel + data.tensionDelta));
            }
            this._rebuildAdventureSessionSummary();
            this.render(false);
        });

        this._adventureHandlersRegistered = true;
    }

    private _rememberAdventureParticipant(playerName: string | null): void {
        if (!playerName) return;
        if (!this.adventureSessionParticipants.includes(playerName)) {
            this.adventureSessionParticipants.push(playerName);
        }
    }

    private _rebuildAdventureSessionSummary(): void {
        const parts: string[] = [];
        if (this.adventureScene?.title) {
            parts.push(`Escena actual: ${this.adventureScene.title}.`);
        }
        if (this.adventureSessionParticipants.length) {
            parts.push(`Participants: ${this.adventureSessionParticipants.join(', ')}.`);
        }
        if (this.discoveredClues.length) {
            parts.push(`Pistes descobertes: ${this.discoveredClues.slice(0, 3).join(' · ')}${this.discoveredClues.length > 3 ? '…' : ''}.`);
        }
        if (this.metNpcs.length) {
            parts.push(`NPCs trobats: ${this.metNpcs.slice(0, 3).join(' · ')}${this.metNpcs.length > 3 ? '…' : ''}.`);
        }
        const latestStoryBeat = [...this.adventureLog].reverse().find(line => !line.startsWith('Tu:'));
        if (latestStoryBeat) {
            parts.push(`Últim desenvolupament: ${latestStoryBeat.slice(0, 220)}${latestStoryBeat.length > 220 ? '…' : ''}.`);
        }
        this.adventureSessionSummary = parts.length ? parts.join(' ') : this.adventureSessionSummary;
    }

    private _formatAdventureDate(value?: string): string {
        if (!value) return '';
        try {
            return new Date(value).toLocaleString();
        } catch (_e) {
            return value;
        }
    }

    private _applyVttActions(actions: VTTAction[]): void {
        // Reuse the existing post-processor if available
        try {
            game.aiGM?.postProcessor?.applyActions?.(actions);
        } catch (e) {
            console.warn('[AI-GM Adventure] Failed to apply VTT actions:', e);
        }
        void this._executeActions(actions);
    }

    private _showIntentConfirmationDialog(data: IntentConfirmationPayload): void {
        if (!this.adventureSessionId) return;
        const sessionId = this.adventureSessionId;
        const ws = game.aiGM?.wsClient;
        if (!ws) return;

        const d = new Dialog({
            title: 'Confirmar intenció',
            content: `<p>${this._escapeHtml(data?.question ?? 'Vols continuar amb aquesta acció?')}</p>`,
            buttons: {
                yes: {
                    icon: '<i class="fas fa-check"></i>',
                    label: 'Sí',
                    callback: () => ws.confirmIntent(true, sessionId)
                },
                no: {
                    icon: '<i class="fas fa-times"></i>',
                    label: 'No',
                    callback: () => ws.confirmIntent(false, sessionId)
                },
                rephrase: {
                    icon: '<i class="fas fa-comment"></i>',
                    label: 'Reformular',
                    callback: () => ws.confirmIntent('rephrase', sessionId)
                }
            },
            default: 'yes'
        });
        d.render(true);
    }

}
