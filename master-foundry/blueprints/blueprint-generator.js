/**
 * Blueprint Generator - Creates fillable character blueprints from schema
 * The blueprint is a contract that defines what CAN be filled for a character
 */

import { SchemaExtractor } from '../schema/schema-extractor.js';

export class BlueprintGenerator {
    constructor() {
        this.schemaExtractor = new SchemaExtractor();
    }

    /**
     * Generate a complete character blueprint for a given actor type
     * @param {string} actorType - The type of actor to generate blueprint for
     * @param {Array<string>} selectedFields - Optional: specific field paths to include (if not provided, includes all)
     * @returns {Object} Complete character blueprint
     */
    generateBlueprint(actorType, selectedFields = null) {
        // Extract base schema
        const actorSchema = this.schemaExtractor.extractActorType(actorType);

        // Filter fields if selectedFields is provided
        let fields = actorSchema.fields;
        if (selectedFields && Array.isArray(selectedFields)) {
            fields = fields.filter(field => selectedFields.includes(field.path));
        }

        // Get item types that are relevant for this actor
        const itemSchemas = this._getRelevantItemSchemas(actorType);

        // Build the blueprint
        const blueprint = {
            systemId: this.schemaExtractor.systemId,
            systemVersion: this.schemaExtractor.systemVersion,
            actorType: actorType,
            timestamp: Date.now(),

            // Actor fields that can be filled
            actor: {
                type: actorType,
                fields: fields,
                coreFields: this._getCoreFields()
            },

            // Item types that can be added to this actor
            items: itemSchemas,

            // Constraints and validation rules
            constraints: [],

            // System-specific metadata
            metadata: {
                systemName: game.system.title,
                actorTypeLabel: this.schemaExtractor.getActorTypeLabels()[actorType]
            }
        };


        return blueprint;
    }

    /**
     * Generate a simplified blueprint for AI consumption
     * @param {string} actorType - The type of actor
     * @param {Array<string>} selectedFields - Optional: specific field paths to include
     * @returns {Object} Simplified blueprint optimized for AI
     */
    generateAIBlueprint(actorType, selectedFields = null) {
        const fullBlueprint = this.generateBlueprint(actorType, selectedFields);

        return {
            systemId: fullBlueprint.systemId,
            actorType: fullBlueprint.actorType,

            // Simplified field descriptions for AI
            actorFields: this._simplifyFieldsForAI(fullBlueprint.actor.fields),

            // Available item types with simplified fields
            availableItems: Object.entries(fullBlueprint.items).map(([itemType, schema]) => ({
                type: itemType,
                label: schema.label,
                fields: this._simplifyFieldsForAI(schema.fields),
                repeatable: true
            })),

            // Constraints in human-readable form
            constraints: fullBlueprint.constraints.map(c => c.description || c.type),

            // Core fields like name, img
            coreFields: fullBlueprint.actor.coreFields,

            // Example structure
            example: this._generateExample(fullBlueprint)
        };
    }

    /**
     * Get core actor fields (name, img, etc.) that exist on all actors
     * @private
     */
    _getCoreFields() {
        return [
            {
                key: 'name',
                path: 'name',
                type: 'string',
                label: 'Name',
                required: true
            },
            {
                key: 'img',
                path: 'img',
                type: 'string',
                label: 'Portrait Image',
                required: false,
                default: 'icons/svg/mystery-man.svg'
            }
        ];
    }

    /**
     * Get relevant item schemas for this actor type
     * @private
     */
    _getRelevantItemSchemas(actorType) {
        const itemSchemas = this.schemaExtractor.extractItemSchemas();
        const relevantItems = {};

        for (const [itemType, schema] of Object.entries(itemSchemas.itemTypes)) {

            relevantItems[itemType] = {
                type: itemType,
                label: this.schemaExtractor.getItemTypeLabels()[itemType],
                fields: schema,
                repeatable: true
            };
        }

        return relevantItems;
    }

    /**
     * Simplify field list for AI consumption
     * @private
     */
    _simplifyFieldsForAI(fields) {
        return fields.map(field => {
            const simplified = {
                path: field.path,
                type: field.type,
                label: field.label
            };

            if (field.required) simplified.required = true;
            if (field.default !== undefined) simplified.default = field.default;
            if (field.min !== undefined) simplified.min = field.min;
            if (field.max !== undefined) simplified.max = field.max;
            if (field.choices) simplified.choices = field.choices;

            return simplified;
        });
    }

    /**
     * Generate an example character structure
     * @private
     */
    _generateExample(blueprint) {
        const example = {
            actor: {
                name: "Example Character",
                type: blueprint.actorType
            },
            items: []
        };

        // Add example values for a few fields
        const exampleFields = blueprint.actor.fields.slice(0, 3);
        if (exampleFields.length > 0) {
            example.actor.system = {};
            for (const field of exampleFields) {
                this._setNestedValue(example.actor.system, field.path.replace('system.', ''), field.default || this._getExampleValue(field.type));
            }
        }

        // Add example item if available
        const firstItemType = Object.keys(blueprint.items)[0];
        if (firstItemType) {
            example.items.push({
                type: firstItemType,
                name: `Example ${blueprint.items[firstItemType].label}`
            });
        }

        return example;
    }

    /**
     * Get example value for a field type
     * @private
     */
    _getExampleValue(type) {
        switch (type) {
            case 'number': return 0;
            case 'string': return '';
            case 'boolean': return false;
            case 'resource': return { value: 0, max: 0 };
            default: return null;
        }
    }

    /**
     * Set a nested value in an object using dot notation path
     * @private
     */
    _setNestedValue(obj, path, value) {
        const parts = path.split('.');
        let current = obj;

        for (let i = 0; i < parts.length - 1; i++) {
            if (!current[parts[i]]) {
                current[parts[i]] = {};
            }
            current = current[parts[i]];
        }

        current[parts[parts.length - 1]] = value;
    }

    /**
     * Validate character data against blueprint
     * @param {Object} characterData - The character data to validate
     * @param {Object} blueprint - The blueprint to validate against
     * @returns {Object} Validation result with errors array
     */
    validateCharacter(characterData, blueprint) {
        const errors = [];

        // Validate core fields
        if (!characterData.actor || !characterData.actor.name) {
            errors.push({
                field: 'name',
                message: 'Character name is required'
            });
        }

        if (characterData.actor && characterData.actor.type !== blueprint.actorType) {
            errors.push({
                field: 'type',
                message: `Actor type mismatch: expected ${blueprint.actorType}, got ${characterData.actor.type}`
            });
        }

        // Validate required fields
        for (const field of blueprint.actor.fields) {
            if (field.required) {
                const value = this._getNestedValue(characterData.actor, field.path);
                if (value === undefined || value === null) {
                    errors.push({
                        field: field.path,
                        message: `Required field ${field.label} is missing`
                    });
                }
            }
        }

        // Validate constraints
        for (const constraint of blueprint.constraints) {
            const constraintError = this._validateConstraint(characterData, constraint);
            if (constraintError) {
                errors.push(constraintError);
            }
        }


        return {
            valid: errors.length === 0,
            errors: errors
        };
    }

    /**
     * Validate a specific constraint
     * @private
     */
    _validateConstraint(characterData, constraint) {
        switch (constraint.type) {
            case 'pointBudget':
                return this._validatePointBudget(characterData, constraint);
            case 'range':
                return this._validateRange(characterData, constraint);
            default:
                return null;
        }
    }

    /**
     * Validate point budget constraint
     * @private
     */
    _validatePointBudget(characterData, constraint) {
        const values = this._collectValuesAtPath(characterData.actor, constraint.path);
        const total = values.reduce((sum, v) => sum + (v || 0), 0);

        if (constraint.exact && total !== constraint.total) {
            return {
                field: constraint.path,
                message: `${constraint.description}: must total exactly ${constraint.total}, got ${total}`
            };
        }

        if (constraint.max && total > constraint.max) {
            return {
                field: constraint.path,
                message: `${constraint.description}: must not exceed ${constraint.max}, got ${total}`
            };
        }

        return null;
    }

    /**
     * Validate range constraint
     * @private
     */
    _validateRange(characterData, constraint) {
        const value = this._getNestedValue(characterData.actor, constraint.path);

        if (value === undefined || value === null) return null;

        if (constraint.min !== undefined && value < constraint.min) {
            return {
                field: constraint.path,
                message: `${constraint.description}: must be at least ${constraint.min}, got ${value}`
            };
        }

        if (constraint.max !== undefined && value > constraint.max) {
            return {
                field: constraint.path,
                message: `${constraint.description}: must be at most ${constraint.max}, got ${value}`
            };
        }

        return null;
    }

    /**
     * Get nested value from object using dot notation
     * @private
     */
    _getNestedValue(obj, path) {
        if (!obj) return undefined;

        const parts = path.split('.');
        let current = obj;

        for (const part of parts) {
            if (current[part] === undefined) return undefined;
            current = current[part];
        }

        return current;
    }

    /**
     * Collect all values at a given path (for objects with multiple sub-properties)
     * @private
     */
    _collectValuesAtPath(obj, path) {
        const parentObj = this._getNestedValue(obj, path);

        if (!parentObj || typeof parentObj !== 'object') {
            return [];
        }

        const values = [];
        for (const key of Object.keys(parentObj)) {
            if (typeof parentObj[key] === 'object' && parentObj[key].value !== undefined) {
                values.push(parentObj[key].value);
            } else if (typeof parentObj[key] === 'number') {
                values.push(parentObj[key]);
            }
        }

        return values;
    }
}

