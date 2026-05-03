/**
 * Schema Extractor with Caching
 * Runtime introspection of Foundry system data models with performance optimizations
 *
 * @module SchemaExtractor
 */

import type { ActorSchema, FieldDefinition, ActorTypeCache, CachedSchema } from '../types/index.js';

interface ItemSchemaResult {
    systemId: string;
    systemVersion: string;
    itemTypes: Record<string, FieldDefinition[]>;
    timestamp: number;
}

interface ItemTypeSchema {
    systemId: string;
    itemType: string;
    fields: FieldDefinition[];
    timestamp: number;
}

export class SchemaExtractor {
    public readonly systemId: string;
    public readonly systemVersion: string;

    // Cache with TTL
    private static readonly CACHE_TTL = 5 * 60 * 1000; // 5 minutes
    private actorSchemaCache: ActorTypeCache = new Map();
    private actorTypesCache: { types: string[]; timestamp: number } | null = null;
    private actorTypeLabelsCache: { labels: Record<string, string>; timestamp: number } | null = null;
    private itemTypesCache: { types: string[]; timestamp: number } | null = null;
    private itemTypeLabelsCache: { labels: Record<string, string>; timestamp: number } | null = null;

    constructor() {
        this.systemId = game.system.id;
        this.systemVersion = game.system.version;

        const hasDataModels = Object.keys(CONFIG.Actor?.dataModels ?? {}).length > 0;
        const hasGameModel = !!game.model?.Actor;
        const hasLegacyModel = !!game.system?.model?.Actor;

        if (!hasDataModels && !hasGameModel && !hasLegacyModel) {
            console.warn('[Schema Extractor] No model source found (CONFIG.Actor.dataModels, game.model, game.system.model)');
        } else {
            const source = hasDataModels ? 'CONFIG.*.dataModels' : hasGameModel ? 'game.model' : 'game.system.model';
            console.log(`[Schema Extractor] Initialized: ${this.systemId} (source: ${source})`);
        }
    }

    /**
     * Get available actor types (cached)
     */
    getActorTypes(): string[] {
        // Check cache
        if (this.actorTypesCache && this.isCacheValid(this.actorTypesCache.timestamp)) {
            console.log('[Schema Extractor] Using cached actor types');
            return this.actorTypesCache.types;
        }

        // Compute
        console.log('[Schema Extractor] Computing actor types');
        const types = this.computeActorTypes();

        // Cache
        this.actorTypesCache = {
            types,
            timestamp: Date.now()
        };

        return types;
    }

    /**
     * Get actor type labels (cached)
     */
    getActorTypeLabels(): Record<string, string> {
        // Check cache
        if (this.actorTypeLabelsCache && this.isCacheValid(this.actorTypeLabelsCache.timestamp)) {
            console.log('[Schema Extractor] Using cached actor type labels');
            return this.actorTypeLabelsCache.labels;
        }

        // Compute
        console.log('[Schema Extractor] Computing actor type labels');
        const labels = this.computeActorTypeLabels();

        // Cache
        this.actorTypeLabelsCache = {
            labels,
            timestamp: Date.now()
        };

        return labels;
    }

    /**
     * Get available item types (cached)
     */
    getItemTypes(): string[] {
        // Check cache
        if (this.itemTypesCache && this.isCacheValid(this.itemTypesCache.timestamp)) {
            console.log('[Schema Extractor] Using cached item types');
            return this.itemTypesCache.types;
        }

        // Compute
        console.log('[Schema Extractor] Computing item types');
        const types = this.computeItemTypes();

        // Cache
        this.itemTypesCache = {
            types,
            timestamp: Date.now()
        };

        return types;
    }

    /**
     * Get item type labels (cached)
     */
    getItemTypeLabels(): Record<string, string> {
        // Check cache
        if (this.itemTypeLabelsCache && this.isCacheValid(this.itemTypeLabelsCache.timestamp)) {
            console.log('[Schema Extractor] Using cached item type labels');
            return this.itemTypeLabelsCache.labels;
        }

        // Compute
        console.log('[Schema Extractor] Computing item type labels');
        const labels = this.computeItemTypeLabels();

        // Cache
        this.itemTypeLabelsCache = {
            labels,
            timestamp: Date.now()
        };

        return labels;
    }

    /**
     * Extract schema for a specific actor type (cached)
     */
    extractActorType(actorType: string): ActorSchema {
        // Check cache
        const cached = this.actorSchemaCache.get(actorType);
        if (cached && this.isCacheValid(cached.timestamp)) {
            console.log(`[Schema Extractor] Using cached schema for: ${actorType}`);
            return cached.schema;
        }

        // Compute
        console.log(`[Schema Extractor] Computing schema for: ${actorType}`);
        const schema = this.computeActorSchema(actorType);

        // Cache
        this.actorSchemaCache.set(actorType, {
            schema,
            timestamp: Date.now()
        });

        return schema;
    }

    /**
     * Extract schemas for all item types
     */
    extractItemSchemas(): ItemSchemaResult {
        const schemas: Record<string, FieldDefinition[]> = {};
        const itemTypes = this.getItemTypes();

        for (const itemType of itemTypes) {
            try {
                // v14+: TypeDataModel via CONFIG.Item.dataModels
                const dataModel = CONFIG.Item?.dataModels?.[itemType];
                if (dataModel) {
                    const fields = this.extractFromDataModel(dataModel, 'system');
                    if (fields.length > 0) {
                        schemas[itemType] = fields;
                        continue;
                    }
                }

                // v12+: game.model
                if (game.model?.Item?.[itemType]) {
                    schemas[itemType] = this.normalizeSchema(game.model.Item[itemType], 'system');
                    continue;
                }

                // Legacy: game.system.model
                if (game.system?.model?.Item?.[itemType]) {
                    schemas[itemType] = this.normalizeSchema(game.system.model.Item[itemType], 'system');
                    continue;
                }

                console.warn(`[Schema Extractor] No item schema found for ${itemType}`);
            } catch (e) {
                console.warn(`[Schema Extractor] Failed to extract item schema for ${itemType}:`, e);
            }
        }

        return {
            systemId: this.systemId,
            systemVersion: this.systemVersion,
            itemTypes: schemas,
            timestamp: Date.now()
        };
    }

    /**
     * Extract schema for a specific item type
     */
    extractItemType(itemType: string): ItemTypeSchema {
        // v14+: TypeDataModel via CONFIG.Item.dataModels
        const dataModel = CONFIG.Item?.dataModels?.[itemType];
        if (dataModel) {
            const fields = this.extractFromDataModel(dataModel, 'system');
            if (fields.length > 0) {
                return { systemId: this.systemId, itemType, fields, timestamp: Date.now() };
            }
        }

        // v12+: game.model
        if (game.model?.Item?.[itemType]) {
            return {
                systemId: this.systemId,
                itemType,
                fields: this.normalizeSchema(game.model.Item[itemType], 'system'),
                timestamp: Date.now()
            };
        }

        // Legacy: game.system.model
        const itemModel = game.system?.model?.Item as Record<string, any> | undefined;
        if (itemModel?.[itemType]) {
            return {
                systemId: this.systemId,
                itemType,
                fields: this.normalizeSchema(itemModel[itemType], 'system'),
                timestamp: Date.now()
            };
        }

        throw new Error(`Item type "${itemType}" not found in system ${this.systemId}`);
    }

    /**
     * Clear all caches
     */
    clearCache(): void {
        this.actorSchemaCache.clear();
        this.actorTypesCache = null;
        this.actorTypeLabelsCache = null;
        this.itemTypesCache = null;
        this.itemTypeLabelsCache = null;
        console.log('[Schema Extractor] Cache cleared');
    }

    /**
     * Check if cache entry is still valid
     */
    private isCacheValid(timestamp: number): boolean {
        return (Date.now() - timestamp) < SchemaExtractor.CACHE_TTL;
    }

    /**
     * Compute actor types from system model
     */
    private computeActorTypes(): string[] {
        // v14+: CONFIG.Actor.dataModels is authoritative for TypeDataModel-based systems
        if (CONFIG.Actor?.dataModels && Object.keys(CONFIG.Actor.dataModels).length > 0) {
            console.log('[Schema Extractor] Using CONFIG.Actor.dataModels for actor types');
            return Object.keys(CONFIG.Actor.dataModels);
        }

        // v12+: game.model (moved from game.system.model)
        if (game.model?.Actor) {
            console.log('[Schema Extractor] Using game.model.Actor for actor types');
            return Object.keys(game.model.Actor);
        }

        // Legacy (v11 and earlier): game.system.model
        if (game.system?.model?.Actor) {
            console.log('[Schema Extractor] Using legacy game.system.model.Actor for actor types');
            return Object.keys(game.system.model.Actor);
        }

        // Fallback: CONFIG.Actor.typeLabels
        if (CONFIG.Actor?.typeLabels) {
            console.log('[Schema Extractor] Using CONFIG.Actor.typeLabels for actor types');
            return Object.keys(CONFIG.Actor.typeLabels);
        }

        // Fallback: system documentTypes
        if (game.system?.documentTypes?.Actor) {
            console.log('[Schema Extractor] Using system.documentTypes.Actor for actor types');
            return Object.keys(game.system.documentTypes.Actor);
        }

        // Fallback: existing actors
        if (game.actors?.size > 0) {
            const types = new Set<string>();
            for (const actor of game.actors) {
                types.add(actor.type);
            }
            return Array.from(types);
        }

        console.warn('[Schema Extractor] Could not determine actor types, falling back to ["character"]');
        return ['character'];
    }

    /**
     * Compute item types from system model
     */
    private computeItemTypes(): string[] {
        // v14+: CONFIG.Item.dataModels is authoritative for TypeDataModel-based systems
        if (CONFIG.Item?.dataModels && Object.keys(CONFIG.Item.dataModels).length > 0) {
            console.log('[Schema Extractor] Using CONFIG.Item.dataModels for item types');
            return Object.keys(CONFIG.Item.dataModels);
        }

        // v12+: game.model (moved from game.system.model)
        if (game.model?.Item) {
            console.log('[Schema Extractor] Using game.model.Item for item types');
            return Object.keys(game.model.Item);
        }

        // Legacy (v11 and earlier): game.system.model
        if (game.system?.model?.Item) {
            console.log('[Schema Extractor] Using legacy game.system.model.Item for item types');
            return Object.keys(game.system.model.Item);
        }

        if (CONFIG.Item?.typeLabels) {
            console.log('[Schema Extractor] Using CONFIG.Item.typeLabels for item types');
            return Object.keys(CONFIG.Item.typeLabels);
        }

        // Fallback: system documentTypes
        if (game.system?.documentTypes?.Item) {
            console.log('[Schema Extractor] Using system.documentTypes.Item for item types');
            return Object.keys(game.system.documentTypes.Item);
        }

        console.warn('[Schema Extractor] Could not determine item types');
        return [];
    }

    /**
     * Compute actor type labels
     */
    private computeActorTypeLabels(): Record<string, string> {
        const types = this.getActorTypes();
        const labels: Record<string, string> = {};

        for (const type of types) {
            // Try to get localized label
            const key = `TYPES.Actor.${type}`;
            const localized = game.i18n?.localize(key);
            labels[type] = (localized && localized !== key) ? localized : this.capitalize(type);
        }

        return labels;
    }

    /**
     * Compute item type labels
     */
    private computeItemTypeLabels(): Record<string, string> {
        const types = this.getItemTypes();
        const labels: Record<string, string> = {};

        for (const type of types) {
            // Try to get localized label
            const key = `TYPES.Item.${type}`;
            const localized = game.i18n?.localize(key);
            labels[type] = (localized && localized !== key) ? localized : this.capitalize(type);
        }

        return labels;
    }

    /**
     * Compute actor schema
     */
    private computeActorSchema(actorType: string): ActorSchema {
        // v14+: TypeDataModel via CONFIG.Actor.dataModels
        const dataModel = CONFIG.Actor?.dataModels?.[actorType];
        if (dataModel) {
            try {
                const schemaFields = this.extractFromDataModel(dataModel, 'system');
                if (schemaFields.length > 0) {
                    console.log(`[Schema Extractor] Extracted ${schemaFields.length} fields from TypeDataModel for ${actorType}`);
                    return { type: actorType, fields: schemaFields };
                }
            } catch (e) {
                console.warn(`[Schema Extractor] TypeDataModel extraction failed for ${actorType}:`, e);
            }
        }

        // v12+: game.model
        if (game.model?.Actor?.[actorType]) {
            return {
                type: actorType,
                fields: this.normalizeSchema(game.model.Actor[actorType], 'system')
            };
        }

        // Legacy: game.system.model
        if (game.system?.model?.Actor?.[actorType]) {
            const actorModel = game.system.model.Actor as Record<string, any>;
            return {
                type: actorType,
                fields: this.normalizeSchema(actorModel[actorType], 'system')
            };
        }

        console.warn(`[Schema Extractor] No model for ${actorType}, using fallback`);
        return {
            type: actorType,
            fields: this.fallbackExtraction(actorType)
        };
    }

    /**
     * Extract field definitions from a TypeDataModel class.
     * Calls defineSchema() and walks the resulting DataField tree.
     */
    private extractFromDataModel(modelClass: any, basePath: string): FieldDefinition[] {
        // Try static schema property first (compiled schema), then defineSchema()
        let schemaObj: any = null;

        if (modelClass.schema?.fields) {
            schemaObj = modelClass.schema.fields;
        } else if (typeof modelClass.defineSchema === 'function') {
            schemaObj = modelClass.defineSchema();
        }

        if (!schemaObj || typeof schemaObj !== 'object') {
            return [];
        }

        return this.normalizeDataModelSchema(schemaObj, basePath);
    }

    /**
     * Walk a DataField schema (from TypeDataModel.defineSchema() or schema.fields)
     * and produce flat FieldDefinition entries.
     */
    private normalizeDataModelSchema(schema: any, basePath: string): FieldDefinition[] {
        const fields: FieldDefinition[] = [];

        // Handle Map-like objects (schema.fields for SchemaField) or plain objects
        const entries: Array<[string, any]> = schema instanceof Map
            ? Array.from(schema.entries())
            : Object.entries(schema);

        for (const [key, field] of entries) {
            const fieldPath = `${basePath}.${key}`;

            if (!field || typeof field !== 'object') continue;

            const constructorName = field.constructor?.name ?? '';

            if (constructorName === 'SchemaField' || constructorName === 'EmbeddedDataField') {
                // Recurse into nested SchemaField
                const innerFields = field.fields ?? field.schema?.fields;
                if (innerFields) {
                    fields.push(...this.normalizeDataModelSchema(innerFields, fieldPath));
                }
            } else if (constructorName === 'ArrayField' || constructorName === 'SetField') {
                fields.push({
                    path: fieldPath,
                    type: 'array',
                    label: field.label || this.humanize(key),
                    required: field.required ?? false,
                    default: field.initial
                });
            } else if (constructorName.endsWith('Field')) {
                // Leaf DataField (NumberField, StringField, BooleanField, etc.)
                fields.push(this.extractDataFieldMetadata(key, field, fieldPath));
            }
        }

        return fields;
    }

    /**
     * Extract metadata from a v14 DataField instance.
     */
    private extractDataFieldMetadata(key: string, field: any, path: string): FieldDefinition {
        const constructorName = field.constructor?.name ?? '';
        let type = 'string';

        if (constructorName.includes('Number')) type = 'number';
        else if (constructorName.includes('Boolean')) type = 'boolean';
        else if (constructorName.includes('Array') || constructorName.includes('Set')) type = 'array';
        else if (constructorName.includes('Object') || constructorName.includes('Schema')) type = 'object';
        else if (constructorName.includes('HTML')) type = 'string';

        const definition: FieldDefinition = {
            path,
            type,
            label: field.label || field.hint || this.humanize(key),
            required: field.required ?? false,
            default: field.initial
        };

        if (field.min !== undefined) definition.min = field.min;
        if (field.max !== undefined) definition.max = field.max;
        if (field.choices) {
            definition.choices = Array.isArray(field.choices)
                ? field.choices
                : Object.keys(field.choices);
        }

        return definition;
    }

    /**
     * Normalize a Foundry schema into field definitions
     */
    private normalizeSchema(schema: any, basePath: string): FieldDefinition[] {
        const fields: FieldDefinition[] = [];

        if (!schema || typeof schema !== 'object') {
            return fields;
        }

        for (const [key, value] of Object.entries(schema)) {
            const fieldPath = `${basePath}.${key}`;

            if (this.isDataField(value)) {
                fields.push(this.extractFieldMetadata(key, value, fieldPath));
            } else if (this.isSchemaField(value)) {
                const nestedFields = this.normalizeSchema((value as any).fields || value, fieldPath);
                fields.push(...nestedFields);
            } else if (typeof value === 'object' && value !== null) {
                const nestedFields = this.normalizeSchema(value, fieldPath);
                fields.push(...nestedFields);
            }
        }

        return fields;
    }

    /**
     * Check if value is a DataField
     */
    private isDataField(value: any): boolean {
        return value?.constructor?.name?.endsWith('Field') ||
               value?._isFoundryDataField === true;
    }

    /**
     * Check if value is a SchemaField
     */
    private isSchemaField(value: any): boolean {
        return value?.constructor?.name === 'SchemaField' || value?.fields;
    }

    /**
     * Extract metadata from a DataField
     */
    private extractFieldMetadata(key: string, field: any, path: string): FieldDefinition {
        return {
            path,
            type: this.getFieldType(field),
            label: this.getFieldLabel(key, path),
            required: field.required ?? false,
            default: field.initial,
            min: field.min,
            max: field.max,
            choices: field.choices
        };
    }

    /**
     * Get field type
     */
    private getFieldType(field: any): string {
        const constructor = field?.constructor?.name || '';

        if (constructor.includes('Number')) return 'number';
        if (constructor.includes('String')) return 'string';
        if (constructor.includes('Boolean')) return 'boolean';
        if (constructor.includes('Array')) return 'array';
        if (constructor.includes('Object')) return 'object';

        // Check for custom types
        if (field.min !== undefined && field.max !== undefined) return 'resource';

        return 'string';
    }

    /**
     * Get field label with i18n support
     */
    private getFieldLabel(key: string, path: string): string {
        // Try localization
        const i18nKey = `AIGM.Fields.${key}`;
        const localized = game.i18n?.localize(i18nKey);
        if (localized && localized !== i18nKey) {
            return localized;
        }

        // Fallback to humanized key
        return this.humanize(key);
    }

    /**
     * Fallback extraction from existing actor
     */
    private fallbackExtraction(actorType: string): FieldDefinition[] {
        // Try to find an existing actor of this type
        const existingActor = game.actors?.find((a: any) => a.type === actorType);

        if (existingActor?.system) {
            return this.extractFromObject(existingActor.system, 'system');
        }

        return [];
    }

    /**
     * Extract fields from a plain object
     */
    private extractFromObject(obj: any, basePath: string): FieldDefinition[] {
        const fields: FieldDefinition[] = [];

        for (const [key, value] of Object.entries(obj)) {
            const fieldPath = `${basePath}.${key}`;
            const fieldType = typeof value;

            if (fieldType === 'object' && value !== null && !Array.isArray(value)) {
                fields.push(...this.extractFromObject(value, fieldPath));
            } else {
                fields.push({
                    path: fieldPath,
                    type: this.inferType(value),
                    label: this.humanize(key),
                    default: value
                });
            }
        }

        return fields;
    }

    /**
     * Infer type from value
     */
    private inferType(value: any): string {
        if (typeof value === 'number') return 'number';
        if (typeof value === 'boolean') return 'boolean';
        if (Array.isArray(value)) return 'array';
        return 'string';
    }

    /**
     * Capitalize first letter
     */
    private capitalize(str: string): string {
        return str.charAt(0).toUpperCase() + str.slice(1);
    }

    /**
     * Convert camelCase or snake_case to human readable
     */
    private humanize(str: string): string {
        return str
            .replace(/([A-Z])/g, ' $1')
            .replace(/_/g, ' ')
            .replace(/^./, char => char.toUpperCase())
            .trim();
    }
}

