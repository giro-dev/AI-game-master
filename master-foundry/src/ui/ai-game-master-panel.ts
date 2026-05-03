/**
 * AI Game Master Panel
 * Central hub for all AI-powered game master tools in Foundry VTT.
 *
 * Tabs: Characters · Items · Library · Session · System
 */

import { FieldTreeBuilder } from '../utils/field-tree-builder.js';
import { CharacterDataSanitizer } from '../utils/character-data-sanitizer.js';
import { CharacterGenerationService } from '../services/character-generation-service.js';
import type {
    FieldDefinition,
    CharacterData,
    ValidationError,
    ChatEntry,
    BookInfo,
    VTTAction
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

        // Fetch reference character status
        let referenceCharacter: any = null;
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
            books: this.books,

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
            referenceCharacter
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

        // Dynamic result-card buttons (delegated)
        html.find('#char-result').on('click', '[data-action="create-character"]', this._onCreateCharacter.bind(this));
        html.find('#char-result').on('click', '[data-action="export-json"]', this._onExportJSON.bind(this));
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
            const request = this._charService.buildRequest({
                prompt,
                actorType: this.selectedActorType,
                blueprint,
                language,
                sessionId
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
            const refChar = await this._fetchReferenceCharacter();
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

            // ── Register with CorrectionTracker so edits are sent as feedback ──
            game.aiGM?.correctionTracker?.track(
                minimalActor.id,
                aiSystemData as Record<string, unknown>,
                actorType,
            );

            this.characterData = null;
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
            this.render(false);
        } catch (e: any) {
            ui.notifications.error(`Reconnect failed: ${e.message}`);
        }
    }

    /* ================================================================== */
    /*  Shared helpers                                                     */
    /* ================================================================== */

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
            combat: game.combat ? { round: game.combat.round, turn: game.combat.turn, currentCombatantId: game.combat.combatant?.tokenId } : null
        };
    }

    private _stripHtml(html: string): string {
        if (!html) return '';
        const tmp = document.createElement('DIV');
        tmp.innerHTML = html;
        return tmp.textContent || tmp.innerText || '';
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
}

