/**
 * System Snapshot Collector
 *
 * Gathers rich runtime introspection data from the active Foundry VTT system
 * to enable the AI to "learn" any game system without hardcoded adapters.
 *
 * Collects: schemas, compendium samples, CONFIG enums, existing actor/item examples,
 * value distributions, and detected constraints.
 */

import { SchemaExtractor } from '../schema/schema-extractor.js';
import type { SystemSnapshot, RollMechanicsSnapshot, RollTriggerField, DerivedFieldInfo } from '../types/index.js';

export class SystemSnapshotCollector {
    private readonly schemaExtractor: SchemaExtractor;

    constructor() {
        this.schemaExtractor = new SchemaExtractor();
    }

    /**
     * Collect a full system snapshot for the active Foundry system.
     */
    async collectSnapshot(): Promise<SystemSnapshot> {
        console.log('[AI-GM Snapshot] Collecting system snapshot...');

        const snapshot: SystemSnapshot = {
            systemId: game.system.id,
            systemVersion: game.system.version,
            systemTitle: game.system.title,
            foundryVersion: game.version,
            worldId: game.world?.id || null,
            timestamp: Date.now(),

            // 1. Schema introspection
            schemas: this._collectSchemas(),

            // 2. System CONFIG enums and labels
            configData: this._collectConfigData(),

            // 3. Compendium samples (capped per type)
            compendiumSamples: await this._collectCompendiumSamples(),

            // 4. Existing actor/item examples from the world
            worldExamples: this._collectWorldExamples(),

            // 5. Detected value distributions from existing data
            valueDistributions: this._collectValueDistributions(),

            // 6. System-specific metadata (template.json data if available)
            templateData: this._collectTemplateData(),

            // 7. Active adapter hints (if any adapter is registered)
            adapterHints: this._collectAdapterHints(),

            // 8. Roll mechanics detection
            rollMechanics: this._collectRollMechanics(),

            // 9. Derived/computed field detection
            derivedFields: this._collectDerivedFields()
        };

        console.log(`[AI-GM Snapshot] Snapshot collected: ${snapshot.schemas.actorTypes.length} actor types, ${snapshot.schemas.itemTypes.length} item types`);
        return snapshot;
    }

    // ─── 1. Schema Introspection ────────────────────────────────────────

    private _collectSchemas(): any {
        const actorSchemas: Record<string, any> = {};
        const itemSchemas: Record<string, any> = {};

        const actorTypes = this.schemaExtractor.getActorTypes();
        const itemTypes = this.schemaExtractor.getItemTypes();

        for (const type of actorTypes) {
            try {
                const schema = this.schemaExtractor.extractActorType(type);
                actorSchemas[type] = {
                    fields: schema.fields,
                    fieldCount: schema.fields.length
                };
            } catch (e) {
                console.warn(`[AI-GM Snapshot] Failed to extract actor schema for ${type}:`, e);
            }
        }

        for (const type of itemTypes) {
            try {
                const schema = this.schemaExtractor.extractItemType(type);
                itemSchemas[type] = {
                    fields: schema.fields,
                    fieldCount: schema.fields.length
                };
            } catch (e) {
                console.warn(`[AI-GM Snapshot] Failed to extract item schema for ${type}:`, e);
            }
        }

        return {
            actorTypes,
            itemTypes,
            actors: actorSchemas,
            items: itemSchemas
        };
    }

    // ─── 2. System CONFIG Data ──────────────────────────────────────────

    private _collectConfigData(): any {
        const configData: Record<string, any> = {};

        if (CONFIG.Actor?.typeLabels) {
            configData.actorTypeLabels = { ...CONFIG.Actor.typeLabels };
        }
        if (CONFIG.Item?.typeLabels) {
            configData.itemTypeLabels = { ...CONFIG.Item.typeLabels };
        }

        const systemConfigKey = this._findSystemConfigKey();
        if (systemConfigKey && CONFIG[systemConfigKey]) {
            configData.systemConfig = this._safeSerialize(CONFIG[systemConfigKey], 3);
        }

        if (CONFIG.statusEffects) {
            configData.statusEffects = CONFIG.statusEffects.map((e: any) => ({
                id: e.id,
                label: e.label || e.name,
                icon: e.icon
            }));
        }

        return configData;
    }

    /**
     * Find the system-specific CONFIG key (e.g., CONFIG.DND5E, CONFIG.HITOS)
     */
    private _findSystemConfigKey(): string | null {
        const candidates = [
            game.system.id.toUpperCase(),
            game.system.id,
            game.system.id.charAt(0).toUpperCase() + game.system.id.slice(1)
        ];

        for (const key of candidates) {
            if (CONFIG[key] && typeof CONFIG[key] === 'object') {
                return key;
            }
        }
        return null;
    }

    // ─── 3. Compendium Samples ──────────────────────────────────────────

    private async _collectCompendiumSamples(): Promise<any> {
        const MAX_SAMPLES_PER_TYPE = 5;
        const samples: { actors: Record<string, any[]>; items: Record<string, any[]> } = { actors: {}, items: {} };

        for (const pack of game.packs) {
            try {
                if (!pack.metadata?.packageName || pack.metadata.packageName === 'world') continue;

                const docType = pack.documentName;
                if (docType !== 'Actor' && docType !== 'Item') continue;

                const index = await pack.getIndex();
                const sampleEntries = Array.from(index).slice(0, MAX_SAMPLES_PER_TYPE);

                for (const entry of sampleEntries) {
                    try {
                        const doc = await pack.getDocument((entry as any)._id);
                        if (!doc) continue;

                        const type = doc.type;
                        const targetMap = docType === 'Actor' ? samples.actors : samples.items;

                        if (!targetMap[type]) targetMap[type] = [];
                        if (targetMap[type].length >= MAX_SAMPLES_PER_TYPE) continue;

                        targetMap[type].push(this._extractDocumentSample(doc, docType));
                    } catch (_e) {
                        // Skip individual documents that fail
                    }
                }
            } catch (e) {
                console.warn(`[AI-GM Snapshot] Failed to sample compendium ${pack.collection}:`, e);
            }
        }

        return samples;
    }

    /**
     * Extract a lightweight sample from a Foundry document
     */
    private _extractDocumentSample(doc: any, docType: string): any {
        const sample: any = {
            name: doc.name,
            type: doc.type,
            system: this._safeSerialize(doc.system, 4)
        };

        // Include items for actors
        if (docType === 'Actor' && doc.items?.size > 0) {
            sample.items = [];
            for (const item of doc.items) {
                if (sample.items.length >= 10) break;
                sample.items.push({
                    name: item.name,
                    type: item.type,
                    system: this._safeSerialize(item.system, 3)
                });
            }
        }

        return sample;
    }

    // ─── 4. World Examples ──────────────────────────────────────────────

    private _collectWorldExamples(): any {
        const MAX_EXAMPLES = 3;
        const examples: { actors: Record<string, any[]>; items: Record<string, any[]> } = { actors: {}, items: {} };

        if (game.actors?.size > 0) {
            for (const actor of game.actors) {
                const type = actor.type;
                if (!examples.actors[type]) examples.actors[type] = [];
                if (examples.actors[type].length >= MAX_EXAMPLES) continue;

                examples.actors[type].push({
                    name: actor.name,
                    type: actor.type,
                    system: this._safeSerialize(actor.system, 4),
                    itemCount: actor.items?.size || 0,
                    itemTypes: actor.items ? [...new Set(actor.items.map((i: any) => i.type))] : []
                });
            }
        }

        if (game.items?.size > 0) {
            for (const item of game.items) {
                const type = item.type;
                if (!examples.items[type]) examples.items[type] = [];
                if (examples.items[type].length >= MAX_EXAMPLES) continue;

                examples.items[type].push({
                    name: item.name,
                    type: item.type,
                    system: this._safeSerialize(item.system, 3)
                });
            }
        }

        return examples;
    }

    // ─── 5. Value Distributions ─────────────────────────────────────────

    private _collectValueDistributions(): any {
        const distributions: Record<string, any> = {};

        if (game.actors?.size < 2) return distributions;

        const actorsByType: Record<string, any[]> = {};
        for (const actor of game.actors) {
            if (!actorsByType[actor.type]) actorsByType[actor.type] = [];
            actorsByType[actor.type].push(actor);
        }

        for (const [type, actors] of Object.entries(actorsByType)) {
            if (actors.length < 2) continue;

            distributions[type] = {};
            const flatValues = actors.map((a: any) => this._flattenObject(a.system, 'system'));

            const allKeys = new Set<string>();
            flatValues.forEach(fv => Object.keys(fv).forEach(k => allKeys.add(k)));

            for (const key of allKeys) {
                const values = flatValues
                    .map(fv => fv[key])
                    .filter((v: any) => typeof v === 'number' && !isNaN(v));

                if (values.length < 2) continue;

                distributions[type][key] = {
                    min: Math.min(...values),
                    max: Math.max(...values),
                    avg: Math.round(values.reduce((a: number, b: number) => a + b, 0) / values.length * 10) / 10,
                    samples: values.length
                };
            }
        }

        return distributions;
    }

    // ─── 6. Template Data ───────────────────────────────────────────────

    private _collectTemplateData(): any {
        const templateData: Record<string, any> = {};

        if (game.system?.template) {
            if (game.system.template.Actor) {
                templateData.actorTemplate = this._safeSerialize(game.system.template.Actor, 3);
            }
            if (game.system.template.Item) {
                templateData.itemTemplate = this._safeSerialize(game.system.template.Item, 3);
            }
        }

        return templateData;
    }

    // ─── 7. Adapter Hints ───────────────────────────────────────────────

    private _collectAdapterHints(): any {
        if (!game.aiGM?.adapterRegistry?.activeAdapter) return null;

        const adapter = game.aiGM.adapterRegistry.activeAdapter;
        const hints: any = {
            adapterId: adapter.systemId,
            adapterName: adapter.getName()
        };

        if (typeof adapter.getAIInstructions === 'function') {
            hints.aiInstructions = adapter.getAIInstructions();
        }

        return hints;
    }

    // ─── Utility Methods ────────────────────────────────────────────────

    // ─── 8. Roll Mechanics Detection ────────────────────────────────────

    private _collectRollMechanics(): RollMechanicsSnapshot | null {
        try {
            const diceFormulas = new Set<string>();
            const rollTriggerFields: RollTriggerField[] = [];
            let skillAsItem = false;

            // Sample items to detect roll/action fields and dice formulas
            const itemSources: any[] = [];
            if (game.items?.size > 0) {
                for (const item of game.items) {
                    if (itemSources.length >= 30) break;
                    itemSources.push(item);
                }
            }
            // Also sample embedded items from actors
            if (game.actors?.size > 0) {
                for (const actor of game.actors) {
                    if (actor.items?.size > 0) {
                        for (const item of actor.items) {
                            if (itemSources.length >= 50) break;
                            itemSources.push(item);
                        }
                    }
                    if (itemSources.length >= 50) break;
                }
            }

            // Detect dice formula patterns from item data
            const diceRegex = /\b(\d*d\d+(?:\s*[\+\-]\s*(?:\d+|@\w[\w.]*))*)(?:\s*\/\s*\w+)?\b/gi;

            for (const item of itemSources) {
                const flat = this._flattenObject(item.system, 'system');
                for (const [key, value] of Object.entries(flat)) {
                    if (typeof value === 'string') {
                        const matches = value.match(diceRegex);
                        if (matches) {
                            matches.forEach(m => diceFormulas.add(m.trim()));
                            rollTriggerFields.push({
                                path: key,
                                type: 'string',
                                context: 'formula field'
                            });
                        }
                    }

                    // Detect action-type fields
                    const lowerKey = key.toLowerCase();
                    if (lowerKey.includes('actiontype') || lowerKey.includes('activation') ||
                        lowerKey.includes('rolltype') || lowerKey.includes('attackbonus')) {
                        rollTriggerFields.push({
                            path: key,
                            type: typeof value,
                            context: 'item action'
                        });
                    }
                }

                // Check if skills are items (PF2e pattern)
                const itemType = (item.type || '').toLowerCase();
                if (itemType === 'skill' || itemType === 'lore') {
                    skillAsItem = true;
                }
            }

            // Detect dice formulas from CONFIG (some systems register their dice there)
            const systemConfigKey = this._findSystemConfigKey();
            if (systemConfigKey && CONFIG[systemConfigKey]) {
                const configStr = JSON.stringify(this._safeSerialize(CONFIG[systemConfigKey], 2));
                const configMatches = configStr.match(diceRegex);
                if (configMatches) {
                    configMatches.forEach(m => diceFormulas.add(m.trim()));
                }
            }

            // Infer success model from dice patterns
            const successModel = this._inferSuccessModel(Array.from(diceFormulas));

            // Deduplicate roll trigger fields by path
            const seenPaths = new Set<string>();
            const uniqueTriggers = rollTriggerFields.filter(f => {
                if (seenPaths.has(f.path)) return false;
                seenPaths.add(f.path);
                return true;
            });

            return {
                diceFormulas: Array.from(diceFormulas).slice(0, 20),
                rollTriggerFields: uniqueTriggers.slice(0, 30),
                successModel,
                skillAsItem
            };
        } catch (e) {
            console.warn('[AI-GM Snapshot] Failed to collect roll mechanics:', e);
            return null;
        }
    }

    /**
     * Infer the success model from detected dice formulas
     */
    private _inferSuccessModel(formulas: string[]): RollMechanicsSnapshot['successModel'] {
        if (formulas.length === 0) return 'unknown';

        const joined = formulas.join(' ').toLowerCase();

        // PbtA: 2d6 is the signature
        if (formulas.some(f => /^2d6$/i.test(f.trim()))) return 'pbta';

        // Count hits: Xd6 patterns common in Shadowrun, WoD (variable pool)
        if (formulas.some(f => /^\d*d6$/i.test(f.trim())) && !joined.includes('d20')) {
            // If we see lots of d6-only formulas with no d20, likely count_hits
            const d6Only = formulas.filter(f => /^\d*d6$/i.test(f.trim()));
            if (d6Only.length > formulas.length / 2) return 'count_hits';
        }

        // Target number: d20 systems (D&D, PF2e)
        if (formulas.some(f => /d20/i.test(f))) return 'target_number';

        return 'unknown';
    }

    // ─── 9. Derived Fields Detection ────────────────────────────────────

    private _collectDerivedFields(): Record<string, DerivedFieldInfo[]> {
        const result: Record<string, DerivedFieldInfo[]> = {};

        if (!game.actors?.size) return result;

        for (const actor of game.actors) {
            const type = actor.type;
            if (result[type]) continue; // One pass per type

            const derived: DerivedFieldInfo[] = [];

            try {
                // Walk the actor.system object and detect properties that look derived
                this._detectDerivedFieldsRecursive(actor.system, 'system', derived, actor);
            } catch (e) {
                console.warn(`[AI-GM Snapshot] Failed to detect derived fields for type ${type}:`, e);
            }

            if (derived.length > 0) {
                result[type] = derived;
            }
        }

        return result;
    }

    /**
     * Recursively walk an object detecting fields that are getters or have
     * value/max patterns suggesting derivation.
     */
    private _detectDerivedFieldsRecursive(
        obj: any, prefix: string, out: DerivedFieldInfo[], actor: any
    ): void {
        if (!obj || typeof obj !== 'object' || out.length >= 50) return;

        const proto = Object.getPrototypeOf(obj);
        for (const key of Object.keys(obj)) {
            if (key.startsWith('_') || key.startsWith('#')) continue;
            const fullPath = `${prefix}.${key}`;
            const value = obj[key];

            // Check if it's a getter on the prototype (= computed/derived)
            if (proto) {
                const descriptor = Object.getOwnPropertyDescriptor(proto, key);
                if (descriptor?.get) {
                    out.push({ path: fullPath, isDerived: true, sourceHint: 'getter property' });
                    continue;
                }
            }

            // Detect value/max pairs where max looks derived
            if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
                if ('value' in value && 'max' in value) {
                    out.push({ path: fullPath, isDerived: false, sourceHint: 'resource (value/max)' });
                }
                // Recurse
                this._detectDerivedFieldsRecursive(value, fullPath, out, actor);
            }
        }
    }

    /**
     * Safely serialize an object with depth limit to avoid circular refs
     */
    private _safeSerialize(obj: any, maxDepth: number = 3, currentDepth: number = 0): any {
        if (currentDepth >= maxDepth) return '[depth limit]';
        if (obj === null || obj === undefined) return obj;
        if (typeof obj !== 'object') return obj;
        if (Array.isArray(obj)) {
            return obj.slice(0, 20).map(item => this._safeSerialize(item, maxDepth, currentDepth + 1));
        }

        const result: Record<string, any> = {};
        const entries = Object.entries(obj);
        for (const [key, value] of entries.slice(0, 50)) {
            if (key.startsWith('_') || key.startsWith('#')) continue;
            if (typeof value === 'function') continue;

            try {
                result[key] = this._safeSerialize(value, maxDepth, currentDepth + 1);
            } catch (_e) {
                result[key] = '[serialize error]';
            }
        }
        return result;
    }

    /**
     * Flatten a nested object into dot-notation keys
     */
    private _flattenObject(obj: any, prefix: string = ''): Record<string, any> {
        const result: Record<string, any> = {};
        if (!obj || typeof obj !== 'object') return result;

        for (const [key, value] of Object.entries(obj)) {
            const fullKey = prefix ? `${prefix}.${key}` : key;
            if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
                Object.assign(result, this._flattenObject(value, fullKey));
            } else {
                result[fullKey] = value;
            }
        }
        return result;
    }
}

