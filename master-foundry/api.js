/**
 * Handle AI response for action execution
 */
export async function handleAiResponse(ai) {
    if (ai.narration) {
        ChatMessage.create({ content: ai.narration, speaker: { alias: "AI Game Master" } });
    }

    for (let action of ai.actions || []) {
        switch(action.type) {

            case "createToken":
                const scene = game.scenes.active;
                await scene.createEmbeddedDocuments("Token", [{
                    name: action.name,
                    img: action.img,
                    x: action.x,
                    y: action.y,
                    actorId: action.actorId
                }]);
                break;

            case "applyDamage":
                const actor = game.actors.get(action.target);
                if (actor) {
                    await actor.applyDamage(action.amount);
                }
                break;

            case "moveToken":
                const moveToken = canvas.tokens.get(action.tokenId);
                if (moveToken) {
                    await moveToken.document.update({x: action.x, y: action.y});
                }
                break;

            case "useAbility":
                // Find the token and use the specified ability
                const abilityToken = canvas.tokens.get(action.tokenId);
                if (abilityToken && abilityToken.actor) {
                    const item = abilityToken.actor.items.get(action.abilityId);
                    if (item) {
                        // Use the item (rolls dice, shows chat card, etc.)
                        await item.use({}, { event: null });
                    }
                }
                break;

            case "rollAbilityCheck":
                // Roll an ability check (STR, DEX, etc.)
                const checkToken = canvas.tokens.get(action.tokenId);
                if (checkToken && checkToken.actor) {
                    await checkToken.actor.rollAbilityTest(action.ability);
                }
                break;

            case "rollSkillCheck":
                // Roll a skill check
                const skillToken = canvas.tokens.get(action.tokenId);
                if (skillToken && skillToken.actor) {
                    await skillToken.actor.rollSkill(action.skill);
                }
                break;

            case "rollSavingThrow":
                // Roll a saving throw
                const saveToken = canvas.tokens.get(action.tokenId);
                if (saveToken && saveToken.actor) {
                    await saveToken.actor.rollAbilitySave(action.ability);
                }
                break;
        }
    }
}

/**
 * Character Generation API
 */
export const CharacterAPI = {
    /**
     * Get blueprint for a specific actor type
     * @param {string} actorType - The actor type
     * @returns {Object} Blueprint
     */
    getBlueprint(actorType) {
        if (!game.aiGM?.blueprintGenerator) {
            throw new Error('Blueprint generator not initialized');
        }
        return game.aiGM.blueprintGenerator.generateAIBlueprint(actorType);
    },

    /**
     * Get all available actor types
     * @returns {Array<string>}
     */
    getActorTypes() {
        if (!game.aiGM?.blueprintGenerator) {
            throw new Error('Blueprint generator not initialized');
        }
        return game.aiGM.blueprintGenerator.schemaExtractor.getActorTypes();
    },

    /**
     * Validate character data against blueprint
     * @param {Object} characterData
     * @param {string} actorType
     * @returns {Object} Validation result
     */
    validateCharacter(characterData, actorType) {
        if (!game.aiGM?.blueprintGenerator) {
            throw new Error('Blueprint generator not initialized');
        }

        const blueprint = game.aiGM.blueprintGenerator.generateBlueprint(actorType);
        return game.aiGM.blueprintGenerator.validateCharacter(characterData, blueprint);
    },

    /**
     * Create character from AI-generated data
     * @param {Object} characterData
     * @returns {Actor} Created actor
     */
    async createCharacter(characterData) {
        // Validate
        const validation = this.validateCharacter(characterData, characterData.actor.type);

        if (!validation.valid) {
            console.warn('[AI-GM] Character validation warnings:', validation.errors);
        }

        // Apply system adapter preprocessing
        const adapter = game.aiGM?.adapterRegistry?.getActive();
        if (adapter) {
            characterData = adapter.preprocessAIData(characterData);
        }

        // Create actor
        const actor = await Actor.create(characterData.actor);

        if (!actor) {
            throw new Error('Failed to create actor');
        }

        // Create items
        if (characterData.items && characterData.items.length > 0) {
            await actor.createEmbeddedDocuments('Item', characterData.items);
        }

        // Post-process
        if (adapter) {
            await adapter.postProcess(actor);
        }

        return actor;
    },

    /**
     * Explain an existing character (reverse operation)
     * @param {Actor} actor
     * @returns {string} AI-generated description
     */
    async explainCharacter(actor) {
        const characterData = {
            actor: actor.toObject(),
            items: actor.items.map(i => i.toObject())
        };

        try {
            const response = await fetch('http://localhost:8080/gm/character/explain', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    characterData: characterData,
                    systemId: game.system.id
                })
            });

            if (!response.ok) {
                throw new Error(`Server responded with ${response.status}`);
            }

            const data = await response.json();
            return data.explanation;

        } catch (error) {
            console.error('Character explanation error:', error);
            throw error;
        }
    }
};

