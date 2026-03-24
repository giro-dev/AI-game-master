/**
 * Generic Post-Processing Engine
 *
 * Reads constraints and derivation rules from the System Knowledge Profile
 * (fetched from the server) instead of relying on hardcoded per-system adapters.
 *
 * Works alongside adapters as a fallback/complement:
 * - If an adapter exists and is active, it takes priority
 * - If no adapter exists, the profile-based engine handles validation + post-processing
 */

import type { SystemProfile, SystemConstraint, ValidationError } from '../types/index.js';

export class PostProcessingEngine {
    private _profile: SystemProfile | null = null;

    /**
     * Set the System Knowledge Profile (fetched from server).
     */
    setProfile(profile: SystemProfile): void {
        this._profile = profile;
        if (profile) {
            console.log(`[AI-GM PostProcessor] Profile loaded: ${profile.systemId} — ${profile.detectedConstraints?.length || 0} constraints`);
        }
    }

    /**
     * Validate character data against the profile constraints.
     */
    validate(characterData: any, _blueprint: any): ValidationError[] {
        if (!this._profile?.detectedConstraints?.length) return [];

        const errors: ValidationError[] = [];

        for (const constraint of this._profile.detectedConstraints) {
            switch (constraint.type) {
                case 'range':
                    errors.push(...this._validateRange(characterData, constraint));
                    break;
                case 'point_budget':
                    errors.push(...this._validatePointBudget(characterData, constraint));
                    break;
                case 'required':
                    errors.push(...this._validateRequired(characterData, constraint));
                    break;
                case 'enum':
                    errors.push(...this._validateEnum(characterData, constraint));
                    break;
            }
        }

        return errors;
    }

    /**
     * Enhance a blueprint with constraints from the profile.
     */
    enhanceBlueprint(blueprint: any): any {
        if (!this._profile?.detectedConstraints?.length) return blueprint;

        for (const constraint of this._profile.detectedConstraints) {
            blueprint.constraints = blueprint.constraints || [];
            // Push a description string — backend expects List<String>
            const desc = constraint.description
                || `${constraint.type} on ${constraint.fieldPath}`;
            blueprint.constraints.push(desc);
        }

        // Add value ranges as metadata
        if (this._profile.valueRanges) {
            blueprint.metadata = blueprint.metadata || {};
            blueprint.metadata.valueRanges = this._profile.valueRanges;
        }

        // Add creation steps as metadata
        if (this._profile.characterCreationSteps?.length) {
            blueprint.metadata = blueprint.metadata || {};
            blueprint.metadata.creationSteps = this._profile.characterCreationSteps;
        }

        return blueprint;
    }

    /**
     * Get AI instructions derived from the profile.
     */
    getAIInstructions(): string {
        if (!this._profile) return '';

        const parts: string[] = [];

        if (this._profile.systemSummary) {
            parts.push(`System: ${this._profile.systemSummary}`);
        }

        if (this._profile.characterCreationSteps?.length) {
            parts.push('Character Creation Steps:');
            this._profile.characterCreationSteps.forEach((step, i) => {
                parts.push(`  ${i + 1}. ${step}`);
            });
        }

        if (this._profile.detectedConstraints?.length) {
            parts.push('Constraints:');
            for (const c of this._profile.detectedConstraints) {
                parts.push(`  - ${c.description}`);
            }
        }

        return parts.join('\n');
    }

    // ─── Private Validation Methods ─────────────────────────────────────

    private _validateRange(characterData: any, constraint: SystemConstraint): ValidationError[] {
        const errors: ValidationError[] = [];
        const params = constraint.parameters || {};
        const value = this._getNestedValue(characterData.actor, constraint.fieldPath);

        if (value !== undefined && typeof value === 'number') {
            if (params.min !== undefined && value < params.min) {
                errors.push({
                    field: constraint.fieldPath,
                    message: `${constraint.fieldPath} (${value}) is below minimum (${params.min})`
                });
            }
            if (params.max !== undefined && value > params.max) {
                errors.push({
                    field: constraint.fieldPath,
                    message: `${constraint.fieldPath} (${value}) exceeds maximum (${params.max})`
                });
            }
        }

        return errors;
    }

    private _validatePointBudget(characterData: any, constraint: SystemConstraint): ValidationError[] {
        const errors: ValidationError[] = [];
        const params = constraint.parameters || {};
        const parentObj = this._getNestedValue(characterData.actor, constraint.fieldPath);

        if (parentObj && typeof parentObj === 'object') {
            let total = 0;
            for (const value of Object.values(parentObj)) {
                const num = typeof value === 'object' ? ((value as any).value || 0) : value;
                if (typeof num === 'number') total += num;
            }

            if (params.total !== undefined && total > params.total) {
                errors.push({
                    field: constraint.fieldPath,
                    message: `${constraint.description}: total (${total}) exceeds budget (${params.total})`
                });
            }
        }

        return errors;
    }

    private _validateRequired(characterData: any, constraint: SystemConstraint): ValidationError[] {
        const value = this._getNestedValue(characterData.actor, constraint.fieldPath);
        if (value === undefined || value === null || value === '') {
            return [{
                field: constraint.fieldPath,
                message: `${constraint.fieldPath} is required`
            }];
        }
        return [];
    }

    private _validateEnum(characterData: any, constraint: SystemConstraint): ValidationError[] {
        const params = constraint.parameters || {};
        const value = this._getNestedValue(characterData.actor, constraint.fieldPath);

        if (value !== undefined && params.choices && !params.choices.includes(value)) {
            return [{
                field: constraint.fieldPath,
                message: `${constraint.fieldPath} must be one of: ${params.choices.join(', ')}`
            }];
        }
        return [];
    }

    private _getNestedValue(obj: any, path: string): any {
        if (!obj || !path) return undefined;
        const parts = path.split('.');
        let current = obj;
        for (const part of parts) {
            if (current === undefined || current === null) return undefined;
            current = current[part];
        }
        return current;
    }
}

