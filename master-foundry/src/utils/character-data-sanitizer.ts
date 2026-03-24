/**
 * Character Data Sanitizer
 * Validates and sanitizes character data to prevent NaN, null, undefined values
 *
 * @module CharacterDataSanitizer
 */

import type { CharacterData, ValidationResult } from '../types/index.js';

export class CharacterDataSanitizer {
    /**
     * Sanitize character data to prevent NaN and undefined values
     */
    static sanitize(characterData: CharacterData | null): CharacterData | null {
        if (!characterData) {
            console.warn('[Character Sanitizer] Received null/undefined character data');
            return null;
        }

        // Deep clone to avoid modifying original
        const sanitized = foundry.utils.deepClone(characterData) as CharacterData;

        // Sanitize actor system data
        if (sanitized.actor?.system) {
            sanitized.actor.system = this.sanitizeValue(sanitized.actor.system, 'actor.system');
        }

        // Sanitize items
        if (sanitized.items) {
            sanitized.items = sanitized.items.map((item, index) => {
                if (item.system) {
                    item.system = this.sanitizeValue(item.system, `items[${index}].system`);
                }
                return item;
            });
        }

        console.log('[Character Sanitizer] Character data sanitized successfully');
        return sanitized;
    }

    /**
     * Recursively sanitize a value
     */
    private static sanitizeValue(value: any, path: string = ''): any {
        if (value === null || value === undefined) {
            console.warn(`[Character Sanitizer] Null/undefined value at: ${path}`);
            return null;
        }

        if (typeof value === 'number') {
            return this.sanitizeNumber(value, path);
        }

        if (typeof value === 'string' || typeof value === 'boolean') {
            return value;
        }

        if (Array.isArray(value)) {
            return value.map((item, index) =>
                this.sanitizeValue(item, `${path}[${index}]`)
            );
        }

        if (typeof value === 'object') {
            return this.sanitizeObject(value, path);
        }

        return value;
    }

    /**
     * Sanitize a number value
     */
    private static sanitizeNumber(value: number, path: string): number {
        if (isNaN(value) || !isFinite(value)) {
            console.warn(`[Character Sanitizer] Invalid number at: ${path}, replacing with 0`);
            return 0;
        }
        return value;
    }

    /**
     * Sanitize an object recursively
     */
    private static sanitizeObject(obj: Record<string, any>, path: string): Record<string, any> {
        const sanitized: Record<string, any> = {};

        for (const [key, val] of Object.entries(obj)) {
            const newPath = path ? `${path}.${key}` : key;
            sanitized[key] = this.sanitizeValue(val, newPath);
        }

        return sanitized;
    }

    /**
     * Validate character data structure
     */
    static validate(characterData: CharacterData | null): ValidationResult {
        const errors: Array<{ field: string; message: string }> = [];

        if (!characterData) {
            errors.push({ field: 'root', message: 'Character data is null or undefined' });
            return { valid: false, errors };
        }

        if (!characterData.actor) {
            errors.push({ field: 'actor', message: 'Actor data is missing' });
        } else {
            if (!characterData.actor.name) {
                errors.push({ field: 'actor.name', message: 'Actor name is required' });
            }
            if (!characterData.actor.type) {
                errors.push({ field: 'actor.type', message: 'Actor type is required' });
            }
        }

        return {
            valid: errors.length === 0,
            errors
        };
    }
}

