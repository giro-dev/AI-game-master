/**
 * Schema Extractor - Runtime introspection of Foundry system data models
 * Converts Foundry's native schema definitions into system-neutral JSON descriptors
 */

export class SchemaExtractor {
    constructor() {
        this.systemId = game.system.id;
        this.systemVersion = game.system.version;
    }

    /**
     * Extract all actor type schemas from the current system
     * @returns {Object} Map of actor types to their schema definitions
     */
    extractActorSchemas() {
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
        const actorModel = game.system.model.Actor;

        if (!actorModel[actorType]) {
            throw new Error(`Actor type "${actorType}" not found in system ${this.systemId}`);
        }

        return {
            systemId: this.systemId,
            actorType: actorType,
            fields: this._normalizeSchema(actorModel[actorType], `system`),
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
        return Object.keys(game.system.model.Actor);
    }

    /**
     * Get available item types in the current system
     * @returns {Array<string>} List of item type identifiers
     */
    getItemTypes() {
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
}

