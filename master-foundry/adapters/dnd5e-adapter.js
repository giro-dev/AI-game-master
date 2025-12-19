/**
 * D&D 5e System Adapter
 * Provides D&D 5e-specific rules, validation, and derived calculations
 */

import { BaseSystemAdapter } from './base-adapter.js';

export class Dnd5eAdapter extends BaseSystemAdapter {
    constructor() {
        super();
        this.systemId = 'dnd5e';
    }

    isCompatible() {
        return game.system.id === 'dnd5e';
    }

    enhanceBlueprint(blueprint) {
        // D&D 5e uses point buy or standard array for abilities
        if (blueprint.actorType === 'character') {
            blueprint.constraints.push({
                type: 'pointBudget',
                path: 'system.abilities',
                total: 27,
                description: 'Point Buy Total (27 points)',
                method: 'pointBuy'
            });

            // Add metadata
            blueprint.metadata.pointBuyRules = {
                8: 0,
                9: 1,
                10: 2,
                11: 3,
                12: 4,
                13: 5,
                14: 7,
                15: 9
            };

            blueprint.metadata.abilityScores = ['str', 'dex', 'con', 'int', 'wis', 'cha'];
        }
    }

    validate(characterData, blueprint) {
        const errors = [];

        if (blueprint.actorType === 'character' && characterData.actor?.system?.abilities) {
            const abilities = characterData.actor.system.abilities;

            // Validate ability score range
            for (const [ability, data] of Object.entries(abilities)) {
                const value = data.value || data;

                if (typeof value === 'number') {
                    if (value < 3 || value > 20) {
                        errors.push({
                            field: `system.abilities.${ability}`,
                            message: `Ability score ${ability.toUpperCase()} must be between 3 and 20`
                        });
                    }
                }
            }

            // Validate point buy if constraint exists
            const pointBuyConstraint = blueprint.constraints.find(c => c.type === 'pointBudget');
            if (pointBuyConstraint) {
                const pointTotal = this._calculatePointBuy(abilities);
                if (pointTotal > 27) {
                    errors.push({
                        field: 'system.abilities',
                        message: `Point buy total (${pointTotal}) exceeds 27 points`
                    });
                }
            }
        }

        return errors;
    }

    derive(actor) {
        if (!actor.system?.abilities) return;

        // Calculate ability modifiers (done automatically by dnd5e system)
        // But we can trigger prepareData to ensure it's updated
        actor.prepareData();
    }

    async postProcess(actor) {
        if (actor.type !== 'character') return;

        // Ensure character has basic items if none exist
        if (actor.items.size === 0) {
            console.log(`[AI-GM D&D5e] Character ${actor.name} has no items, may want to add defaults`);
        }

        // Trigger derived data calculation
        this.derive(actor);
    }

    isItemTypeRelevant(actorType, itemType) {
        // Characters can have most item types
        if (actorType === 'character') {
            return ['weapon', 'equipment', 'consumable', 'tool', 'loot',
                    'class', 'spell', 'feat', 'background', 'race'].includes(itemType);
        }

        // NPCs can have weapons, equipment, features
        if (actorType === 'npc') {
            return ['weapon', 'equipment', 'consumable', 'spell', 'feat'].includes(itemType);
        }

        return true;
    }

    getAIInstructions() {
        return `
D&D 5e Specific Rules:
- Ability scores (str, dex, con, int, wis, cha) range from 3-20, typically 8-15 for new characters
- Point buy system allows 27 points total (8=0pts, 9=1pt, 10=2pts, 11=3pts, 12=4pts, 13=5pts, 14=7pts, 15=9pts)
- Characters need a race (e.g., Human, Elf, Dwarf) and class (e.g., Fighter, Wizard, Rogue)
- Proficiency bonus starts at +2 for level 1 characters
- HP is determined by class hit die + CON modifier
- Common skills: acrobatics (dex), athletics (str), arcana (int), perception (wis), stealth (dex), etc.
`;
    }

    preprocessAIData(characterData) {
        // Ensure abilities have correct structure
        if (characterData.actor?.system?.abilities) {
            const abilities = characterData.actor.system.abilities;

            for (const [key, value] of Object.entries(abilities)) {
                // If AI provided just a number, wrap it in proper structure
                if (typeof value === 'number') {
                    abilities[key] = { value: value };
                }
            }
        }

        return characterData;
    }

    /**
     * Calculate point buy cost for ability scores
     * @private
     */
    _calculatePointBuy(abilities) {
        const costs = {
            8: 0, 9: 1, 10: 2, 11: 3, 12: 4, 13: 5, 14: 7, 15: 9
        };

        let total = 0;
        for (const [ability, data] of Object.entries(abilities)) {
            const value = data.value || data;
            if (typeof value === 'number' && costs[value] !== undefined) {
                total += costs[value];
            }
        }

        return total;
    }
}

