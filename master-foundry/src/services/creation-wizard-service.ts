/**
 * Creation Wizard Service
 * Manages a step-by-step guided character creation flow.
 *
 * Inspired by dnd5e's Advancement system, the wizard walks users through:
 *   1. Template selection (optional preset)
 *   2. Field customization
 *   3. Prompt & generation
 *   4. Preview & edit before creating in Foundry
 *
 * @module CreationWizardService
 */

import type {
    CharacterTemplate,
    CreationWizardStep,
    CreationWizardState,
    CharacterData,
    ValidationError,
    EditableField,
    CharacterPreview,
    FieldDefinition
} from '../types/index.js';

const STEPS: CreationWizardStep[] = ['template', 'fields', 'prompt', 'preview'];

export class CreationWizardService {
    private state: CreationWizardState;

    constructor() {
        this.state = this._defaultState();
    }

    private _defaultState(): CreationWizardState {
        return {
            currentStep: 'template',
            selectedTemplate: null,
            actorType: 'character',
            selectedFields: new Set(),
            prompt: '',
            language: 'en',
            generatedData: null,
            validationErrors: [],
            batchCount: 1,
            batchResults: []
        };
    }

    getState(): CreationWizardState {
        return this.state;
    }

    getCurrentStep(): CreationWizardStep {
        return this.state.currentStep;
    }

    getStepIndex(): number {
        return STEPS.indexOf(this.state.currentStep);
    }

    getTotalSteps(): number {
        return STEPS.length;
    }

    getStepLabel(step: CreationWizardStep): string {
        const labels: Record<CreationWizardStep, string> = {
            template: 'Template',
            fields: 'Fields',
            prompt: 'Generate',
            preview: 'Preview'
        };
        return labels[step];
    }

    getAllSteps(): Array<{ id: CreationWizardStep; label: string; active: boolean; completed: boolean }> {
        const currentIdx = this.getStepIndex();
        return STEPS.map((step, idx) => ({
            id: step,
            label: this.getStepLabel(step),
            active: step === this.state.currentStep,
            completed: idx < currentIdx
        }));
    }

    canGoNext(): boolean {
        const idx = this.getStepIndex();
        if (idx >= STEPS.length - 1) return false;

        switch (this.state.currentStep) {
            case 'template':
                return true;
            case 'fields':
                return this.state.selectedFields.size > 0;
            case 'prompt':
                return this.state.prompt.trim().length > 0;
            case 'preview':
                return this.state.generatedData !== null;
            default:
                return false;
        }
    }

    canGoPrev(): boolean {
        return this.getStepIndex() > 0;
    }

    goNext(): boolean {
        if (!this.canGoNext()) return false;
        const idx = this.getStepIndex();
        this.state.currentStep = STEPS[idx + 1];
        return true;
    }

    goPrev(): boolean {
        if (!this.canGoPrev()) return false;
        const idx = this.getStepIndex();
        this.state.currentStep = STEPS[idx - 1];
        return true;
    }

    goToStep(step: CreationWizardStep): void {
        this.state.currentStep = step;
    }

    applyTemplate(template: CharacterTemplate): void {
        this.state.selectedTemplate = template;
        this.state.actorType = template.actorType;
        this.state.selectedFields = new Set(template.selectedFields);
        this.state.prompt = template.promptText;
        this.state.language = template.language;
    }

    setActorType(actorType: string): void {
        this.state.actorType = actorType;
    }

    setSelectedFields(fields: Set<string>): void {
        this.state.selectedFields = fields;
    }

    setPrompt(prompt: string): void {
        this.state.prompt = prompt;
    }

    setLanguage(language: string): void {
        this.state.language = language;
    }

    setBatchCount(count: number): void {
        this.state.batchCount = Math.max(1, Math.min(10, count));
    }

    setGeneratedData(data: CharacterData): void {
        this.state.generatedData = data;
    }

    addBatchResult(data: CharacterData): void {
        this.state.batchResults.push(data);
    }

    setValidationErrors(errors: ValidationError[]): void {
        this.state.validationErrors = errors;
    }

    /**
     * Build a preview from generated character data with editable fields.
     */
    buildPreview(data: CharacterData, fieldDefs: FieldDefinition[]): CharacterPreview {
        const fieldDefMap = new Map<string, FieldDefinition>();
        for (const fd of fieldDefs) {
            fieldDefMap.set(fd.path, fd);
        }

        const actorEditableFields: EditableField[] = [];
        if (data.actor?.system) {
            this._extractEditableFields(data.actor.system, 'system', fieldDefMap, actorEditableFields);
        }

        const itemPreviews = (data.items || []).map(item => {
            const itemFields: EditableField[] = [];
            if (item.system) {
                this._extractEditableFields(item.system, 'system', new Map(), itemFields);
            }
            return {
                name: item.name,
                type: item.type,
                editableFields: itemFields
            };
        });

        return {
            actor: {
                name: data.actor?.name || 'Unnamed',
                type: data.actor?.type || 'character',
                img: data.actor?.img || 'icons/svg/mystery-man.svg',
                editableFields: actorEditableFields
            },
            items: itemPreviews,
            validationErrors: this.state.validationErrors
        };
    }

    /**
     * Apply preview edits back into character data.
     */
    applyEdits(preview: CharacterPreview): CharacterData | null {
        if (!this.state.generatedData) return null;

        const data: CharacterData = JSON.parse(JSON.stringify(this.state.generatedData));
        data.actor.name = preview.actor.name;
        data.actor.img = preview.actor.img;

        for (const field of preview.actor.editableFields) {
            if (field.modified) {
                this._setNestedValue(data.actor, field.path, field.value);
            }
        }

        for (let i = 0; i < preview.items.length && i < data.items.length; i++) {
            data.items[i].name = preview.items[i].name;
            for (const field of preview.items[i].editableFields) {
                if (field.modified) {
                    this._setNestedValue(data.items[i], field.path, field.value);
                }
            }
        }

        this.state.generatedData = data;
        return data;
    }

    reset(): void {
        this.state = this._defaultState();
    }

    /**
     * Build a template from the current wizard state for saving.
     */
    captureAsTemplate(name: string, description: string): CharacterTemplate {
        return {
            id: `tpl-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
            name,
            description,
            actorType: this.state.actorType,
            promptText: this.state.prompt,
            selectedFields: Array.from(this.state.selectedFields),
            language: this.state.language,
            itemTypes: [],
            tags: [],
            createdAt: Date.now(),
            updatedAt: Date.now()
        };
    }

    private _extractEditableFields(
        obj: Record<string, any>,
        prefix: string,
        fieldDefMap: Map<string, FieldDefinition>,
        result: EditableField[]
    ): void {
        for (const [key, value] of Object.entries(obj)) {
            const path = `${prefix}.${key}`;
            if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
                this._extractEditableFields(value, path, fieldDefMap, result);
            } else {
                const def = fieldDefMap.get(path);
                result.push({
                    path,
                    label: def?.label || key,
                    type: typeof value === 'number' ? 'number' : 'string',
                    value,
                    originalValue: value,
                    min: def?.min,
                    max: def?.max,
                    choices: def?.choices,
                    modified: false
                });
            }
        }
    }

    private _setNestedValue(obj: Record<string, any>, path: string, value: any): void {
        const parts = path.split('.');
        let current = obj;
        for (let i = 0; i < parts.length - 1; i++) {
            if (!(parts[i] in current) || typeof current[parts[i]] !== 'object') {
                current[parts[i]] = {};
            }
            current = current[parts[i]];
        }
        current[parts[parts.length - 1]] = value;
    }
}
