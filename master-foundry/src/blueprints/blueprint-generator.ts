/**
 * Blueprint Generator - Creates fillable character blueprints from schema
 * The blueprint is a contract that defines what CAN be filled for a character
 */

import { SchemaExtractor } from '../schema/schema-extractor.js';
import type { SystemSkillRegistry } from '../skills/system-skill.js';
import type { FieldDefinition, FullBlueprint, AIBlueprint, ValidationResult } from '../types/index.js';

export class BlueprintGenerator {
    public readonly schemaExtractor: SchemaExtractor;
    private skillRegistry: SystemSkillRegistry | null = null;

    constructor() {
        this.schemaExtractor = new SchemaExtractor();
    }

    /** Attach a skill registry for constraint/hint injection. */
    setSkillRegistry(registry: SystemSkillRegistry): void {
        this.skillRegistry = registry;
        this.schemaExtractor.setSkillRegistry(registry);
    }

    /**
     * Generate a complete character blueprint for a given actor type
     */
    generateBlueprint(actorType: string, selectedFields: string[] | null = null): FullBlueprint {
        const actorSchema = this.schemaExtractor.extractActorType(actorType);

        let fields = actorSchema.fields;
        if (selectedFields && Array.isArray(selectedFields)) {
            fields = fields.filter(field => selectedFields.includes(field.path));
        }

        const itemSchemas = this._getRelevantItemSchemas(actorType);

        const blueprint: FullBlueprint = {
            systemId: this.schemaExtractor.systemId,
            systemVersion: this.schemaExtractor.systemVersion,
            actorType,
            timestamp: Date.now(),
            actor: {
                type: actorType,
                fields,
                coreFields: this._getCoreFields()
            },
            items: itemSchemas,
            constraints: [],
            metadata: {
                systemName: game.system.title,
                actorTypeLabel: this.schemaExtractor.getActorTypeLabels()[actorType]
            }
        };

        // Inject skill constraints & hints
        this._applySkillEnhancements(blueprint);

        return blueprint;
    }

    /**
     * Generate a simplified blueprint for AI consumption
     */
    generateAIBlueprint(actorType: string, selectedFields: string[] | null = null): AIBlueprint {
        const fullBlueprint = this.generateBlueprint(actorType, selectedFields);

        const aiBlueprint: AIBlueprint = {
            systemId: fullBlueprint.systemId,
            actorType: fullBlueprint.actorType,
            actorFields: this._simplifyFieldsForAI(fullBlueprint.actor.fields),
            availableItems: Object.entries(fullBlueprint.items).map(([itemType, schema]) => ({
                type: itemType,
                label: schema.label,
                fields: this._simplifyFieldsForAI(schema.fields),
                repeatable: true
            })),
            constraints: fullBlueprint.constraints.map((c: any) => c.description || c.type),
            coreFields: fullBlueprint.actor.coreFields,
            example: this._generateExample(fullBlueprint)
        };

        // Append skill creation hints / steps to the AI blueprint
        if (this.skillRegistry) {
            const hints = this.skillRegistry.getCreationHints();
            if (hints) {
                (aiBlueprint as any).creationHints = hints;
            }
            const steps = this.skillRegistry.getCreationSteps();
            if (steps.length > 0) {
                (aiBlueprint as any).creationSteps = steps;
            }
            const aliases = this.skillRegistry.getFieldAliases();
            if (Object.keys(aliases).length > 0) {
                (aiBlueprint as any).fieldAliases = aliases;
            }
        }

        return aiBlueprint;
    }

    /**
     * Get core actor fields (name, img, etc.) that exist on all actors
     */
    private _getCoreFields(): FieldDefinition[] {
        return [
            {
                path: 'name',
                type: 'string',
                label: 'Name',
                required: true
            },
            {
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
     */
    private _getRelevantItemSchemas(_actorType: string): Record<string, any> {
        const itemSchemas = this.schemaExtractor.extractItemSchemas();
        const relevantItems: Record<string, any> = {};

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
     */
    private _simplifyFieldsForAI(fields: FieldDefinition[]): Partial<FieldDefinition>[] {
        return fields.map(field => {
            const simplified: Partial<FieldDefinition> = {
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
     */
    private _generateExample(blueprint: FullBlueprint): any {
        const example: any = {
            actor: {
                name: 'Example Character',
                type: blueprint.actorType
            },
            items: [] as any[]
        };

        const exampleFields = blueprint.actor.fields.slice(0, 3);
        if (exampleFields.length > 0) {
            example.actor.system = {};
            for (const field of exampleFields) {
                this._setNestedValue(
                    example.actor.system,
                    field.path.replace('system.', ''),
                    field.default || this._getExampleValue(field.type)
                );
            }
        }

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
     */
    private _getExampleValue(type: string): any {
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
     */
    private _setNestedValue(obj: any, path: string, value: any): void {
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
     */
    validateCharacter(characterData: any, blueprint: FullBlueprint): ValidationResult {
        const errors: Array<{ field: string; message: string }> = [];

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

        for (const constraint of blueprint.constraints) {
            const constraintError = this._validateConstraint(characterData, constraint);
            if (constraintError) {
                errors.push(constraintError);
            }
        }

        return {
            valid: errors.length === 0,
            errors
        };
    }

    /**
     * Validate a specific constraint
     */
    private _validateConstraint(characterData: any, constraint: any): { field: string; message: string } | null {
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
     */
    private _validatePointBudget(characterData: any, constraint: any): { field: string; message: string } | null {
        const values = this._collectValuesAtPath(characterData.actor, constraint.path);
        const total = values.reduce((sum: number, v: number) => sum + (v || 0), 0);

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
     */
    private _validateRange(characterData: any, constraint: any): { field: string; message: string } | null {
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
     */
    private _getNestedValue(obj: any, path: string): any {
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
     */
    private _collectValuesAtPath(obj: any, path: string): number[] {
        const parentObj = this._getNestedValue(obj, path);

        if (!parentObj || typeof parentObj !== 'object') {
            return [];
        }

        const values: number[] = [];
        for (const key of Object.keys(parentObj)) {
            if (typeof parentObj[key] === 'object' && parentObj[key].value !== undefined) {
                values.push(parentObj[key].value);
            } else if (typeof parentObj[key] === 'number') {
                values.push(parentObj[key]);
            }
        }

        return values;
    }

    /* ── Skill integration ── */

    /**
     * Inject constraints, creation hints, and default items from active skills.
     */
    private _applySkillEnhancements(blueprint: FullBlueprint): void {
        if (!this.skillRegistry) return;

        // Constraints
        const skillConstraints = this.skillRegistry.getAllConstraints();
        if (skillConstraints.length > 0) {
            blueprint.constraints = blueprint.constraints || [];
            for (const c of skillConstraints) {
                blueprint.constraints.push({
                    type: c.type,
                    path: c.fieldPath,
                    description: c.description,
                    ...c.parameters
                });
            }
        }

        // Creation hints as metadata
        const hints = this.skillRegistry.getCreationHints();
        if (hints) {
            blueprint.metadata = blueprint.metadata || {};
            blueprint.metadata.skillHints = hints;
        }

        // Creation steps
        const steps = this.skillRegistry.getCreationSteps();
        if (steps.length > 0) {
            blueprint.metadata = blueprint.metadata || {};
            blueprint.metadata.skillCreationSteps = steps;
        }

        // Default items
        const defaultItems = this.skillRegistry.getDefaultItems();
        if (defaultItems.length > 0) {
            blueprint.metadata = blueprint.metadata || {};
            blueprint.metadata.defaultItems = defaultItems;
        }
    }
}

