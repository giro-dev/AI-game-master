/**
 * Hitos System Adapter
 * Provides Hitos-specific rules, validation, and derived calculations
 */

import { BaseSystemAdapter } from './base-adapter.js';

export class HitosAdapter extends BaseSystemAdapter {
    constructor() {
        super();
        this.systemId = 'hitos';
    }

    isCompatible() {
        return game.system.id === 'hitos';
    }

    enhanceBlueprint(blueprint) {
        if (blueprint.actorType === 'character') {
            // Hitos typically uses 18 points distributed across attributes
            blueprint.constraints.push({
                type: 'pointBudget',
                path: 'system.attributes',
                total: 18,
                min: 15,
                max: 18,
                description: 'Attribute Points (15-18 total)',
                exact: false
            });

            // Attributes range from 0-10
            const attributes = ['fortaleza', 'reflejos', 'voluntad', 'intelecto', 'percepcion', 'carisma'];
            for (const attr of attributes) {
                blueprint.constraints.push({
                    type: 'range',
                    path: `system.attributes.${attr}.value`,
                    min: 0,
                    max: 10,
                    description: `${attr} must be 0-10`
                });
            }

            // Drama points metadata
            blueprint.metadata.dramaPoints = {
                default: 5,
                description: 'Puntos de Drama iniciales'
            };

            blueprint.metadata.attributes = attributes;
        }
    }

    validate(characterData, blueprint) {
        const errors = [];

        if (blueprint.actorType === 'character' && characterData.actor?.system?.attributes) {
            const attributes = characterData.actor.system.attributes;

            // Validate attribute range (0-10)
            for (const [attr, data] of Object.entries(attributes)) {
                const value = data.value !== undefined ? data.value : data;

                if (typeof value === 'number') {
                    if (value < 0 || value > 10) {
                        errors.push({
                            field: `system.attributes.${attr}`,
                            message: `Attribute ${attr} must be between 0 and 10, got ${value}`
                        });
                    }
                }
            }

            // Validate total points
            const total = Object.values(attributes).reduce((sum, attr) => {
                const value = attr.value !== undefined ? attr.value : attr;
                return sum + (typeof value === 'number' ? value : 0);
            }, 0);

            if (total < 15 || total > 18) {
                errors.push({
                    field: 'system.attributes',
                    message: `Total attribute points must be 15-18, got ${total}`
                });
            }
        }

        return errors;
    }

    derive(actor) {
        if (!actor.system) return;

        // Calculate derived values like Aguante and Resistencia
        // Aguante = Fortaleza + Voluntad
        // Resistencia = Aguante + Drama

        if (actor.system.attributes) {
            const fortaleza = actor.system.attributes.fortaleza?.value || 0;
            const voluntad = actor.system.attributes.voluntad?.value || 0;
            const drama = actor.system.drama?.value || 5;

            if (actor.system.aguante !== undefined) {
                actor.system.aguante = {
                    value: fortaleza + voluntad,
                    max: fortaleza + voluntad
                };
            }

            if (actor.system.resistencia !== undefined) {
                const aguante = fortaleza + voluntad;
                actor.system.resistencia = {
                    value: aguante + drama,
                    max: aguante + drama
                };
            }
        }
    }

    async postProcess(actor) {
        if (actor.type !== 'character') return;

        // Set default drama points if not set
        if (actor.system.drama && actor.system.drama.value === undefined) {
            await actor.update({
                'system.drama.value': 5
            });
        }

        // Calculate derived values
        this.derive(actor);

        // If actor has derived fields, update them
        const updates = {};

        if (actor.system.aguante) {
            const fortaleza = actor.system.attributes?.fortaleza?.value || 0;
            const voluntad = actor.system.attributes?.voluntad?.value || 0;
            updates['system.aguante.value'] = fortaleza + voluntad;
            updates['system.aguante.max'] = fortaleza + voluntad;
        }

        if (actor.system.resistencia) {
            const fortaleza = actor.system.attributes?.fortaleza?.value || 0;
            const voluntad = actor.system.attributes?.voluntad?.value || 0;
            const drama = actor.system.drama?.value || 5;
            const aguante = fortaleza + voluntad;
            updates['system.resistencia.value'] = aguante + drama;
            updates['system.resistencia.max'] = aguante + drama;
        }

        if (Object.keys(updates).length > 0) {
            await actor.update(updates);
        }
    }

    isItemTypeRelevant(actorType, itemType) {
        if (actorType === 'character') {
            // Hitos uses skills, aspects, traits, cyberimplants, etc.
            return ['skill', 'aspect', 'trait', 'cyberimplant', 'weapon', 'armor', 'equipment'].includes(itemType);
        }

        return true;
    }

    getAIInstructions() {
        return `
Hitos Specific Rules:
- Attributes: fortaleza, reflejos, voluntad, intelecto, percepcion, carisma
- Each attribute ranges from 0-10
- Total attribute points should be between 15-18
- Drama points (puntos de drama) typically start at 5
- Aguante = Fortaleza + Voluntad
- Resistencia = Aguante + Drama
- Skills (habilidades) have values typically 1-10 and are linked to attributes
- Aspects (aspectos) are narrative descriptors (usually 3-5 aspects per character)
- Typical aspects: Concepto (concept), Problema (trouble), and 1-3 additional aspects
`;
    }

    preprocessAIData(characterData) {
        // Ensure attributes have correct structure
        if (characterData.actor?.system?.attributes) {
            const attributes = characterData.actor.system.attributes;

            for (const [key, value] of Object.entries(attributes)) {
                // If AI provided just a number, wrap it in proper structure
                if (typeof value === 'number') {
                    attributes[key] = { value: value };
                }
            }
        }

        // Ensure drama has correct structure
        if (characterData.actor?.system?.drama) {
            const drama = characterData.actor.system.drama;
            if (typeof drama === 'number') {
                characterData.actor.system.drama = { value: drama };
            }
        }

        return characterData;
    }
}

