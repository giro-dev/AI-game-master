/**
 * Schema Extractor - Runtime introspection of Foundry system data models
 * Converts Foundry's native schema definitions into system-neutral JSON descriptors
 */

export class SchemaExtractor {
    constructor() {
        this.systemId = game.system.id;
        this.systemVersion = game.system.version;
        
        // Log availability of system model
        if (!game.system?.model) {
            console.warn('[AI-GM SchemaExtractor] game.system.model is not available at initialization');
        } else {
            console.log('[AI-GM SchemaExtractor] Initialized for system:', this.systemId);
            console.log('[AI-GM SchemaExtractor] Actor types available:', Object.keys(game.system.model.Actor || {}));
            console.log('[AI-GM SchemaExtractor] Item types available:', Object.keys(game.system.model.Item || {}));
        }
    }

    /**
     * Extract all actor type schemas from the current system
     * @returns {Object} Map of actor types to their schema definitions
     */
    extractActorSchemas() {
        if (!game.system?.model?.Actor) {
            console.warn('[AI-GM] game.system.model.Actor is not available yet');
            return {
                systemId: this.systemId,
                systemVersion: this.systemVersion,
                actorTypes: {},
                timestamp: Date.now()
            };
        }

        const actorModel = game.system.model.Actor;
        const schemas = {};

        for (const [actorType, schema] of Object.entries(actorModel)) {
            schemas[actorType] = this._normalizeSchema(schema, `system`);
        }

        return {
            systemId: this.systemId,
            systemVersion: this.systemVersion,
            actorTypes: schemas,
            timestamp: Date.now()
        };
    }

    /**
     * Extract all item type schemas from the current system
     * @returns {Object} Map of item types to their schema definitions
     */
    extractItemSchemas() {
        if (!game.system?.model?.Item) {
            console.warn('[AI-GM] game.system.model.Item is not available yet');
            return {
                systemId: this.systemId,
                systemVersion: this.systemVersion,
                itemTypes: {},
                timestamp: Date.now()
            };
        }

        const itemModel = game.system.model.Item;
        const schemas = {};

        for (const [itemType, schema] of Object.entries(itemModel)) {
            schemas[itemType] = this._normalizeSchema(schema, `system`);
        }

        return {
            systemId: this.systemId,
            systemVersion: this.systemVersion,
            itemTypes: schemas,
            timestamp: Date.now()
        };
    }

    /**
     * Extract schema for a specific actor type
     * @param {string} actorType - The actor type to extract
     * @returns {Object} Normalized schema with field metadata
     */
    extractActorType(actorType) {
        // Try standard approach first (Foundry v10+)
        if (game.system?.model?.Actor?.[actorType]) {
            const actorModel = game.system.model.Actor;
            return {
                systemId: this.systemId,
                actorType: actorType,
                fields: this._normalizeSchema(actorModel[actorType], `system`),
                timestamp: Date.now()
            };
        }

        // Fallback: Extract from template actor or first existing actor
        console.warn(`[AI-GM] game.system.model.Actor.${actorType} is not available. Using fallback extraction.`);

        // Try to get template data
        const templateData = this._extractFromTemplate(actorType);
        if (templateData) {
            return {
                systemId: this.systemId,
                actorType: actorType,
                fields: templateData,
                timestamp: Date.now()
            };
        }

        // Last resort: find an existing actor of this type
        const existingActor = game.actors?.find(a => a.type === actorType);
        if (existingActor) {
            console.log(`[AI-GM] Extracting schema from existing actor: ${existingActor.name}`);
            const fields = this._normalizeSchema(existingActor.system, 'system');
            return {
                systemId: this.systemId,
                actorType: actorType,
                fields: fields,
                timestamp: Date.now()
            };
        }

        // No data available
        console.error(`[AI-GM] Cannot extract schema for actor type "${actorType}". No template or existing actors found.`);
        return {
            systemId: this.systemId,
            actorType: actorType,
            fields: [],
            timestamp: Date.now()
        };
    }

    /**
     * Extract schema for a specific item type
     * @param {string} itemType - The item type to extract
     * @returns {Object} Normalized schema with field metadata
     */
    extractItemType(itemType) {
        const itemModel = game.system.model.Item;

        if (!itemModel[itemType]) {
            throw new Error(`Item type "${itemType}" not found in system ${this.systemId}`);
        }

        return {
            systemId: this.systemId,
            itemType: itemType,
            fields: this._normalizeSchema(itemModel[itemType], `system`),
            timestamp: Date.now()
        };
    }

    /**
     * Normalize a Foundry schema object into a flat field list
     * @private
     * @param {Object} schema - The Foundry schema object
     * @param {string} basePath - The base path for field references
     * @returns {Array} Array of field descriptors
     */
    _normalizeSchema(schema, basePath = '') {
        const fields = [];

        for (const [key, definition] of Object.entries(schema)) {
            const fieldPath = basePath ? `${basePath}.${key}` : key;

            // Handle nested objects
            if (this._isPlainObject(definition) && !this._isFieldDefinition(definition)) {
                // Recurse into nested schema
                const nestedFields = this._normalizeSchema(definition, fieldPath);
                fields.push(...nestedFields);
            } else {
                // This is a field definition
                const fieldDescriptor = this._createFieldDescriptor(key, definition, fieldPath);
                fields.push(fieldDescriptor);
            }
        }

        return fields;
    }

    /**
     * Create a normalized field descriptor from Foundry field definition
     * @private
     */
    _createFieldDescriptor(key, definition, path) {
        const descriptor = {
            key: key,
            path: path,
            type: this._inferType(definition),
            label: this._inferLabel(key),
            required: false,
            repeatable: false
        };

        // Extract type-specific metadata
        if (typeof definition === 'number') {
            descriptor.default = definition;
        } else if (typeof definition === 'string') {
            descriptor.default = definition;
        } else if (typeof definition === 'boolean') {
            descriptor.default = definition;
        } else if (this._isPlainObject(definition)) {
            // Handle Foundry field schema objects
            if ('initial' in definition) {
                descriptor.default = definition.initial;
            }
            if ('value' in definition) {
                descriptor.default = definition.value;
            }
            if ('max' in definition) {
                descriptor.max = definition.max;
            }
            if ('min' in definition) {
                descriptor.min = definition.min;
            }
            if ('choices' in definition) {
                descriptor.choices = definition.choices;
            }
            if ('required' in definition) {
                descriptor.required = definition.required;
            }
        }

        return descriptor;
    }

    /**
     * Infer the data type from Foundry field definition
     * @private
     */
    _inferType(definition) {
        if (typeof definition === 'number') return 'number';
        if (typeof definition === 'string') return 'string';
        if (typeof definition === 'boolean') return 'boolean';
        if (Array.isArray(definition)) return 'array';

        if (this._isPlainObject(definition)) {
            // Check for nested value object pattern
            if ('value' in definition && 'max' in definition) {
                return 'resource';
            }
            if ('value' in definition) {
                return this._inferType(definition.value);
            }
            if ('initial' in definition) {
                return this._inferType(definition.initial);
            }
            return 'object';
        }

        return 'unknown';
    }

    /**
     * Generate a human-readable label from field key
     * @private
     */
    _inferLabel(key) {
        // Convert camelCase or snake_case to Title Case
        return key
            .replace(/([A-Z])/g, ' $1')
            .replace(/_/g, ' ')
            .replace(/\b\w/g, c => c.toUpperCase())
            .trim();
    }

    /**
     * Check if value is a plain object
     * @private
     */
    _isPlainObject(value) {
        return value !== null &&
               typeof value === 'object' &&
               !Array.isArray(value) &&
               Object.getPrototypeOf(value) === Object.prototype;
    }

    /**
     * Check if an object is likely a field definition (vs nested schema)
     * @private
     */
    _isFieldDefinition(obj) {
        if (!this._isPlainObject(obj)) return false;

        // Field definitions typically have properties like 'value', 'initial', 'max', 'min'
        const fieldProps = ['value', 'initial', 'max', 'min', 'choices', 'required'];
        const hasFieldProp = fieldProps.some(prop => prop in obj);

        return hasFieldProp;
    }

    /**
     * Get available actor types in the current system
     * @returns {Array<string>} List of actor type identifiers
     */
    getActorTypes() {
        // Try standard approach first (Foundry v10+)
        if (game.system?.model?.Actor) {
            return Object.keys(game.system.model.Actor);
        }

        // Fallback 1: Use CONFIG.Actor.typeLabels (common in many systems)
        if (CONFIG.Actor?.typeLabels) {
            console.log('[AI-GM] Using CONFIG.Actor.typeLabels for actor types');
            return Object.keys(CONFIG.Actor.typeLabels);
        }

        // Fallback 2: Check system template.json data types
        if (game.system?.template?.Actor?.types) {
            console.log('[AI-GM] Using system template Actor types');
            return game.system.template.Actor.types;
        }

        // Fallback 3: Extract from existing actors
        if (game.actors?.size > 0) {
            const types = new Set();
            for (const actor of game.actors) {
                types.add(actor.type);
            }
            console.log('[AI-GM] Extracted actor types from existing actors:', Array.from(types));
            return Array.from(types);
        }

        console.warn('[AI-GM] Could not determine actor types. Falling back to ["character"]');
        return ['character']; // Ultimate fallback
    }

    /**
     * Get available item types in the current system
     * @returns {Array<string>} List of item type identifiers
     */
    getItemTypes() {
        if (!game.system?.model?.Item) {
            console.warn('[AI-GM] game.system.model.Item is not available yet');
            return [];
        }
        return Object.keys(game.system.model.Item);
    }

    /**
     * Get human-readable labels for actor types
     * @returns {Object} Map of actor type to display label
     */
    getActorTypeLabels() {
        const labels = {};
        const actorTypes = this.getActorTypes();

        for (const type of actorTypes) {
            labels[type] = this._inferLabel(type);
        }

        return labels;
    }

    /**
     * Get human-readable labels for item types
     * @returns {Object} Map of item type to display label
     */
    getItemTypeLabels() {
        const labels = {};
        const itemTypes = this.getItemTypes();

        for (const type of itemTypes) {
            labels[type] = this._inferLabel(type);
        }

        return labels;
    }

    /**
     * Extract schema from template data (for systems without exposed model)
     * @private
     */
    _extractFromTemplate(actorType) {
        // Try to get from system template
        if (game.system?.template?.Actor?.[actorType]) {
            console.log(`[AI-GM] Extracting from system template for ${actorType}`);
            return this._normalizeSchema(game.system.template.Actor[actorType], 'system');
        }

        // Try DocumentClass default data
        try {
            const ActorClass = CONFIG.Actor.documentClass;
            if (ActorClass) {
                // Try to create a temporary actor to get its structure
                const tempData = ActorClass.defaultData?.({ type: actorType });
                if (tempData?.system) {
                    console.log(`[AI-GM] Extracting from defaultData for ${actorType}`);
                    return this._normalizeSchema(tempData.system, 'system');
                }
            }
        } catch (error) {
            console.warn('[AI-GM] Could not extract from defaultData:', error);
        }

        return null;
    }
}

