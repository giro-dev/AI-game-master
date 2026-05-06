/**
 * Character Generation Service
 * Handles communication with the backend API for character generation
 *
 * @module CharacterGenerationService
 */

import type {
    CharacterData,
    GenerationRequest,
    ValidationResult,
    CharacterBlueprint
} from '../types/index.js';

interface RequestParams {
    prompt: string;
    actorType: string;
    blueprint: CharacterBlueprint;
    language: string;
    sessionId?: string | null;
    worldId?: string | null;
    referenceCharacter?: {
        systemId: string;
        actorType: string;
        label: string;
        actorData: Record<string, any>;
        items: Array<Record<string, any>>;
        capturedAt?: number;
    } | null;
}

export class CharacterGenerationService {
    private readonly apiBaseUrl: string;

    constructor(apiBaseUrl: string = 'http://localhost:8080') {
        this.apiBaseUrl = apiBaseUrl;
    }

    /**
     * Generate a character via API
     */
    async generateCharacter(request: GenerationRequest): Promise<CharacterData> {
        console.log('[Character Service] Generating character:', request.actorType);

        const response = await fetch(`${this.apiBaseUrl}/gm/character/generate`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(request)
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`Server responded with ${response.status}: ${errorText}`);
        }

        const data = await response.json();

        if (!data.success) {
            throw new Error(data.reasoning || 'Character generation failed');
        }

        console.log('[Character Service] Character generated successfully');
        return data.character as CharacterData;
    }

    /**
     * Build generation request from form data
     */
    buildRequest(params: RequestParams): GenerationRequest {
        return {
            prompt: params.prompt.trim(),
            actorType: params.actorType,
            blueprint: params.blueprint,
            language: params.language || 'en',
            sessionId: params.sessionId || undefined,
            worldId: params.worldId || undefined,
            referenceCharacter: params.referenceCharacter || undefined
        };
    }

    /**
     * Validate request before sending
     */
    validateRequest(request: GenerationRequest): ValidationResult {
        const errors: Array<{ field: string; message: string }> = [];

        if (!request.prompt || request.prompt.trim() === '') {
            errors.push({ field: 'prompt', message: 'Character description is required' });
        }

        if (!request.actorType) {
            errors.push({ field: 'actorType', message: 'Actor type is required' });
        }

        if (!request.blueprint) {
            errors.push({ field: 'blueprint', message: 'Blueprint is required' });
        } else if (!request.blueprint.actorFields || request.blueprint.actorFields.length === 0) {
            errors.push({ field: 'blueprint.actorFields', message: 'No fields selected for generation' });
        }

        return {
            valid: errors.length === 0,
            errors
        };
    }
}
