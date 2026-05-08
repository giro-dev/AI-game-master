/**
 * GeneratePanel — Character & Item generation submodule.
 *
 * Encapsulates all state and logic for the Generate tab (characters + items).
 */

import { FieldTreeBuilder } from '../utils/field-tree-builder.js';
import { CharacterDataSanitizer } from '../utils/character-data-sanitizer.js';
import { CharacterGenerationService } from '../services/character-generation-service.js';
import type { FieldDefinition, CharacterData, ValidationError } from '../types/index.js';
import { getServerUrl } from '../settings.js';
import { showProgress, hideProgress, escapeHtml, type PanelContext } from './panel-utils.js';

export class GeneratePanel {

    // ── State ──
    private selectedActorType: string = 'character';
    private actorFields: FieldDefinition[] = [];
    private selectedFields: Set<string> = new Set();
    private characterData: CharacterData | null = null;
    private validationErrors: ValidationError[] = [];
    private _generationReferenceCharacter: any | null = null;

    private readonly _charService: CharacterGenerationService;

    constructor(private readonly ctx: PanelContext) {
        this._charService = new CharacterGenerationService(getServerUrl);
    }

    getSelectedActorType(): string {
        return this.selectedActorType;
    }

    /* ------------------------------------------------------------------ */
    /*  getData contribution                                                */
    /* ------------------------------------------------------------------ */

    async getData(): Promise<Partial<any>> {
        this._ensureFieldsLoaded();

        const ext = game.aiGM?.blueprintGenerator?.schemaExtractor;
        let actorTypes: string[] = ['character'];
        let actorTypeLabels: Record<string, string> = {};
        let itemTypes: string[] = [];

        try {
            actorTypes = ext?.getActorTypes() ?? ['character'];
            actorTypeLabels = ext?.getActorTypeLabels() ?? {};
            itemTypes = ext?.getItemTypes() ?? [];
        } catch (e) {
            console.warn('[AI-GM GeneratePanel] Schema extraction failed:', e);
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
            console.warn('[AI-GM GeneratePanel] Pack enumeration failed:', e);
        }

        // Fetch reference character for the indicator badge
        let referenceCharacter: any = null;
        if (!this.ctx.getAdventureSessionId()) {
            try {
                const refRes = await fetch(
                    `${getServerUrl()}/gm/character/reference/${encodeURIComponent(game.system.id)}/${encodeURIComponent(this.selectedActorType)}`
                );
                if (refRes.ok) {
                    const refData = await refRes.json();
                    referenceCharacter = {
                        label: refData.label,
                        actorType: refData.actorType,
                        itemCount: refData.items?.length ?? 0,
                        capturedAt: refData.capturedAt ? new Date(refData.capturedAt).toLocaleString() : null,
                    };
                }
            } catch (_e) { /* server unreachable */ }
        }

        return {
            actorTypes: actorTypes.map(id => ({
                id,
                label: actorTypeLabels[id] ?? id,
                selected: id === this.selectedActorType
            })),
            fieldTreeHTML: FieldTreeBuilder.buildTree(this.actorFields, this.selectedFields),
            itemPacks,
            itemTypeNames: itemTypes,
            referenceCharacter
        };
    }

    /* ------------------------------------------------------------------ */
    /*  activateListeners                                                   */
    /* ------------------------------------------------------------------ */

    activateListeners(html: any): void {
        // Characters section
        html.find('#gm-actor-type').on('change', this._onActorTypeChange.bind(this));
        html.find('[data-action="select-all-fields"]').on('click', () => this._toggleAllFields(html, true));
        html.find('[data-action="deselect-all-fields"]').on('click', () => this._toggleAllFields(html, false));
        html.find('#gm-field-tree').on('change', '.field-checkbox', this._onFieldToggle.bind(this));
        html.find('[data-action="generate-character"]').on('click', this._onGenerateCharacter.bind(this));
        html.find('[data-action="view-blueprint"]').on('click', this._onViewBlueprint.bind(this));

        // Dynamic result-card buttons (delegated)
        html.find('#char-result').on('click', '[data-action="create-character"]', this._onCreateCharacter.bind(this));
        html.find('#char-result').on('click', '[data-action="export-json"]', this._onExportJSON.bind(this));

        // Items section
        html.find('[data-action="generate-items"]').on('click', this._onGenerateItems.bind(this));
    }

    /* ================================================================== */
    /*  Characters section                                                  */
    /* ================================================================== */

    private _ensureFieldsLoaded(): void {
        if (this.actorFields.length > 0) return;
        try {
            const schema = game.aiGM?.blueprintGenerator?.schemaExtractor?.extractActorType(this.selectedActorType);
            if (!schema) return;
            this.actorFields = schema.fields;
            this.selectedFields = new Set(this.actorFields.map((f: FieldDefinition) => f.path));
        } catch (e) {
            console.warn('[AI-GM GeneratePanel] Could not load fields:', e);
        }
    }

    private _onActorTypeChange(ev: any): void {
        this.selectedActorType = ev.currentTarget.value;
        try {
            const schema = game.aiGM.blueprintGenerator.schemaExtractor.extractActorType(this.selectedActorType);
            this.actorFields = schema.fields;
            this.selectedFields = new Set(this.actorFields.map((f: FieldDefinition) => f.path));
        } catch (e) {
            console.error('[AI-GM GeneratePanel] Field load error:', e);
        }
        this.ctx.render(false);
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
        const html = this.ctx.element;
        const prompt: string = html.find('#gm-char-prompt').val()?.trim();
        const language: string = html.find('#gm-char-lang').val() || 'ca';

        if (!prompt) return ui.notifications.warn('Please enter a character description.');
        if (this.selectedFields.size === 0) return ui.notifications.warn('Select at least one field.');

        const btn = html.find('[data-action="generate-character"]');
        btn.prop('disabled', true).html('<i class="fas fa-spinner fa-spin"></i> Generating…');
        showProgress(html, 'char', 'Starting generation…', 5);

        const progressHandler = (data: any): void => {
            if (data?.currentStep) showProgress(html, 'char', data.currentStep, data.progress ?? 50);
        };
        game.aiGM?.wsClient?.on('onCharacterGenerationStarted', progressHandler);

        try {
            const selectedArr = Array.from(this.selectedFields);
            let blueprint = game.aiGM.blueprintGenerator.generateAIBlueprint(this.selectedActorType, selectedArr);

            if (game.aiGM.postProcessor) blueprint = game.aiGM.postProcessor.enhanceBlueprint(blueprint);

            const sessionId: string | null = game.aiGM?.wsClient?.getSessionId() ?? null;
            const storedRefChar = await this._fetchReferenceCharacter();
            const referenceCharacter = storedRefChar ?? this._buildImplicitReferenceCharacter();
            this._generationReferenceCharacter = referenceCharacter;

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

            this.characterData = CharacterDataSanitizer.sanitize(charData);
            this.validationErrors = [];

            if (game.aiGM.postProcessor && this.characterData) {
                this.validationErrors = game.aiGM.postProcessor.validate(this.characterData, blueprint);
            }

            this._renderCharacterResult(html);
            hideProgress(html, 'char');
            ui.notifications.info('Character generated!');
        } catch (e: any) {
            console.error('[AI-GM GeneratePanel] Generation error:', e);
            ui.notifications.error(`Generation failed: ${e.message}`);
            hideProgress(html, 'char');
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

            const actorName = actorData.name
                || (aiSystemData as any).nombre
                || (aiSystemData as any).nom
                || (aiSystemData as any).concept
                || 'AI Character';
            const actorType = actorData.type || this.selectedActorType || 'character';

            const refChar = await this._fetchReferenceCharacter() ?? this._generationReferenceCharacter;

            const minimalActor = await Actor.create({
                name: actorName,
                type: actorType,
                img: actorData.img || 'icons/svg/mystery-man.svg'
            });
            if (!minimalActor) throw new Error('Actor creation failed');

            if (Object.keys(aiSystemData).length > 0) {
                const existingSystem = (minimalActor as any).system ?? {};
                const flatUpdate: Record<string, any> = {};
                this._flattenStructureAware(aiSystemData, 'system', flatUpdate, existingSystem);
                await minimalActor.update(flatUpdate);
            }

            if (minimalActor.items.size > 0) {
                await this._syncAISkillsToItems(minimalActor, aiSystemData, refChar);
            }

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
            this.ctx.render(false);
        } catch (e: any) {
            console.error('[AI-GM GeneratePanel] Create character failed:', e);
            ui.notifications.error(`Create failed: ${e.message}`);
        }
    }

    private async _syncAISkillsToItems(actor: any, aiSystemData: Record<string, any>, refChar?: any): Promise<void> {
        if (!actor.items.size) return;

        if (refChar?.items?.length) {
            const updates = this._buildReferenceBasedUpdates(actor, aiSystemData, refChar.items);
            if (updates.length > 0) {
                await actor.updateEmbeddedDocuments('Item', updates);
                return;
            }
        }

        const candidateMaps = this._collectSkillDataMaps(aiSystemData);
        if (candidateMaps.length === 0) return;

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
                }
                break;
            }
        }

        if (updates.length > 0) {
            await actor.updateEmbeddedDocuments('Item', updates);
        }
    }

    private _buildReferenceBasedUpdates(
        actor: any,
        aiSystemData: Record<string, any>,
        refItems: Array<Record<string, any>>
    ): any[] {
        const updates: any[] = [];
        const norm = (s: string): string =>
            s.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9]/g, '');

        const refLookup = new Map<string, Record<string, any>>();
        for (const refItem of refItems) {
            refLookup.set(norm(refItem.name || ''), refItem);
        }

        const skillMaps = this._collectSkillDataMaps(aiSystemData);

        for (const item of actor.items) {
            const refItem = refLookup.get(norm(item.name || ''));
            if (!refItem) continue;

            const refSystem = refItem.system;
            if (!refSystem || typeof refSystem !== 'object') continue;

            const update: Record<string, any> = { _id: item.id };
            let hasUpdate = false;

            for (const [field, refValue] of Object.entries(refSystem)) {
                if (typeof refValue === 'number') {
                    const aiVal = this._findAIValueForItem(item, field, skillMaps, aiSystemData);
                    if (aiVal !== null) { update[`system.${field}`] = aiVal; hasUpdate = true; }
                } else if (typeof refValue === 'object' && refValue !== null && 'value' in refValue && typeof refValue.value === 'number') {
                    const aiVal = this._findAIValueForItem(item, field, skillMaps, aiSystemData);
                    if (aiVal !== null) { update[`system.${field}.value`] = aiVal; hasUpdate = true; }
                }
            }

            if (hasUpdate) updates.push(update);
        }

        return updates;
    }

    private _findAIValueForItem(
        item: any,
        _field: string,
        skillMaps: Array<{ path: string; map: Record<string, any> }>,
        _aiSystemData: Record<string, any>
    ): number | null {
        for (const { map } of skillMaps) {
            const matchKey = this._fuzzyMatchItemToKey(item, Object.keys(map));
            if (matchKey !== null) {
                const val = map[matchKey];
                if (typeof val === 'number') return val;
                if (typeof val === 'object' && val !== null && 'value' in val && typeof val.value === 'number') return val.value;
            }
        }
        return null;
    }

    private _alignItemsToReference(
        aiItems: Array<Record<string, any>>,
        refItems: Array<Record<string, any>>
    ): Array<Record<string, any>> {
        const refByType = new Map<string, Record<string, any>>();
        for (const ri of refItems) {
            if (ri.type && !refByType.has(ri.type)) refByType.set(ri.type, ri);
        }

        return aiItems.map(aiItem => {
            const refTemplate = refByType.get(aiItem.type);
            if (!refTemplate) return aiItem;
            const mergedSystem = this._deepMerge(
                JSON.parse(JSON.stringify(refTemplate.system || {})),
                aiItem.system || {}
            );
            return { ...aiItem, system: mergedSystem };
        });
    }

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

    private _collectSkillDataMaps(system: any, path: string = ''): Array<{ path: string; map: Record<string, any> }> {
        const results: Array<{ path: string; map: Record<string, any> }> = [];
        if (!system || typeof system !== 'object') return results;

        const likelyCandidates = ['habilidades', 'skills', 'abilities', 'competences',
            'competencias', 'habilitats', 'pericias', 'capacidades'];

        for (const [key, value] of Object.entries(system)) {
            if (value && typeof value === 'object' && !Array.isArray(value)) {
                const fullPath = path ? `${path}.${key}` : key;
                const entries = Object.entries(value as Record<string, any>);
                const numericCount = entries.filter(([, v]) =>
                    typeof v === 'number' || (typeof v === 'object' && v && 'value' in v)
                ).length;

                if (entries.length > 2 && numericCount >= entries.length * 0.6) {
                    results.push({ path: fullPath, map: value as Record<string, any> });
                }
                if (likelyCandidates.includes(key.toLowerCase()) && entries.length > 0) {
                    if (!results.find(r => r.path === fullPath)) {
                        results.push({ path: fullPath, map: value as Record<string, any> });
                    }
                }
            }
        }

        return results;
    }

    private _fuzzyMatchItemToKey(item: any, keys: string[]): string | null {
        const norm = (s: string): string =>
            s.toLowerCase()
                .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
                .replace(/[^a-z0-9]/g, '');

        const itemName = norm(item.name || '');
        const itemId = norm(item.system?.identifier || item.flags?.core?.sourceId || '');

        for (const key of keys) {
            const nk = norm(key);
            if (!nk) continue;
            if (nk === itemName || nk === itemId) return key;
            if (itemName.includes(nk) || nk.includes(itemName)) return key;
            if (itemId && (itemId.includes(nk) || nk.includes(itemId))) return key;
        }

        return null;
    }

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
                this._flattenStructureAware(value, path, result, tplValue ?? {});
            } else if (
                (typeof value === 'number' || typeof value === 'string' || typeof value === 'boolean') &&
                tplValue && typeof tplValue === 'object' && !Array.isArray(tplValue) && 'value' in tplValue
            ) {
                result[`${path}.value`] = value;
            } else {
                result[path] = value;
            }
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

        if (updates.length > 0) await actor.updateEmbeddedDocuments('Item', updates);
        if (creates.length > 0) await actor.createEmbeddedDocuments('Item', creates);
    }

    private _buildImplicitReferenceCharacter(): any | null {
        const actors = (Array.from(game.actors ?? []) as any[])
            .filter((actor: any) => actor?.type === this.selectedActorType)
            .sort((a: any, b: any) => {
                const itemDiff = (b?.items?.size ?? 0) - (a?.items?.size ?? 0);
                if (itemDiff !== 0) return itemDiff;
                return Object.keys(b?.system ?? {}).length - Object.keys(a?.system ?? {}).length;
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
            console.warn('[AI-GM GeneratePanel] Failed to build implicit reference character:', e);
            return null;
        }
    }

    private async _fetchReferenceCharacter(): Promise<any | null> {
        try {
            const res = await fetch(
                `${getServerUrl()}/gm/character/reference/${encodeURIComponent(game.system.id)}/${encodeURIComponent(this.selectedActorType)}`
            );
            if (res.ok) return await res.json();
        } catch (_) { /* server unreachable */ }
        return null;
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
    /*  Items section                                                       */
    /* ================================================================== */

    private async _onGenerateItems(_ev: any): Promise<void> {
        const html = this.ctx.element;
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
}
