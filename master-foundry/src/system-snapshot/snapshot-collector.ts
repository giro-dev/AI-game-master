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
import type { SystemSnapshot } from '../types/index.js';

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
            adapterHints: this._collectAdapterHints()
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

        // v14+: Collect TypeDataModel schema info from CONFIG.*.dataModels
        if (CONFIG.Actor?.dataModels) {
            const actorDataModels: Record<string, any> = {};
            for (const [type, model] of Object.entries(CONFIG.Actor.dataModels)) {
                try {
                    if (typeof (model as any).defineSchema === 'function') {
                        const schema = (model as any).defineSchema();
                        actorDataModels[type] = this._safeSerialize(
                            Object.keys(schema).reduce((acc: Record<string, string>, key: string) => {
                                acc[key] = schema[key]?.constructor?.name ?? 'unknown';
                                return acc;
                            }, {}), 3
                        );
                    }
                } catch (_e) { /* skip individual models that fail */ }
            }
            if (Object.keys(actorDataModels).length > 0) {
                templateData.actorDataModels = actorDataModels;
            }
        }

        if (CONFIG.Item?.dataModels) {
            const itemDataModels: Record<string, any> = {};
            for (const [type, model] of Object.entries(CONFIG.Item.dataModels)) {
                try {
                    if (typeof (model as any).defineSchema === 'function') {
                        const schema = (model as any).defineSchema();
                        itemDataModels[type] = this._safeSerialize(
                            Object.keys(schema).reduce((acc: Record<string, string>, key: string) => {
                                acc[key] = schema[key]?.constructor?.name ?? 'unknown';
                                return acc;
                            }, {}), 3
                        );
                    }
                } catch (_e) { /* skip individual models that fail */ }
            }
            if (Object.keys(itemDataModels).length > 0) {
                templateData.itemDataModels = itemDataModels;
            }
        }

        // Legacy: game.system.template (deprecated in v12+)
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

