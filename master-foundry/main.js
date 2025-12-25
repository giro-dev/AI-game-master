import { handleAiResponse, CharacterAPI } from "./api.js";
import { SchemaExtractor } from "./schema/schema-extractor.js";
import { BlueprintGenerator } from "./blueprints/blueprint-generator.js";
import { CharacterGeneratorUI } from "./ui/character-generator.js";
import { ItemGeneratorUI } from "./ui/item-generator.js";
import { WebSocketClient } from "./websocket-client.js";

Hooks.on("ready", () => {
    console.log("AI GM module loaded");

    // Check if game.system.model is available
    if (!game.system?.model) {
        console.error('[AI-GM] game.system.model is not available. The AI GM character generator may not work properly.');
        ui.notifications.warn('AI GM: System model not available. Character generation may be limited.');
    }

    // Register a setting to remember the default item pack
    if (!game.settings?.settings?.has('ai-gm.defaultItemPack')) {
        game.settings.register('ai-gm', 'defaultItemPack', {
            name: 'Default Item Pack',
            scope: 'world',
            config: false,
            type: String,
            default: ''
        });
    }

    // Initialize blueprint generator (system-agnostic)
    const blueprintGenerator = new BlueprintGenerator();

    // Initialize WebSocket client
    const wsClient = new WebSocketClient('http://localhost:8080');

    // Setup WebSocket event handlers
    wsClient.on('onCharacterGenerationCompleted', (data) => {
        console.log('[AI-GM] Character generation completed:', data);
        // You can add custom handling here
    });

    wsClient.on('onImageGenerationCompleted', (data) => {
        console.log('[AI-GM] Image generation completed:', data);
        // Update character/token with new image
        if (data.imagePath) {
            ui.notifications.info(`Image ready: ${data.imagePath}`);
        }
    });

    wsClient.on('onItemGenerationStarted', (data) => {
        ui.notifications.info('Generating items...');
    });

    wsClient.on('onItemGenerationFailed', (message) => {
        ui.notifications.error(`Item generation failed: ${message.error || 'unknown error'}`);
    });

    wsClient.on('onItemGenerationCompleted', async (event) => {
        const { items = [], packId, requestId } = event || {};
        if (!items.length) {
            ui.notifications.warn('No items returned');
            return;
        }
        const pack = packId ? game.packs.get(packId) : null;
        if (!pack) {
            ui.notifications.error(`Pack not found: ${packId || 'none provided'}`);
            return;
        }
        try {
            const created = [];
            for (const itemData of items) {
                const tempItem = await Item.create(itemData, { temporary: true });
                const doc = await pack.importDocument(tempItem);
                created.push(doc.name);
            }
            ui.notifications.info(`Imported ${created.length} items to ${packId}`);
        } catch (err) {
            console.error('[AI-GM] Failed importing items', err);
            ui.notifications.error(`Failed importing items: ${err.message}`);
        }
    });

    // Connect to WebSocket
    wsClient.connect().catch(err => {
        console.warn('[AI-GM] WebSocket connection failed:', err);
        ui.notifications.warn('Real-time updates disabled. Using HTTP only.');
    });

    game.aiGM = {
        // Character generation components
        schemaExtractor: new SchemaExtractor(),
        blueprintGenerator: blueprintGenerator,
        CharacterAPI: CharacterAPI,
        wsClient: wsClient, // Expose WebSocket client

        /**
         * Send a prompt to the AI Game Master along with token abilities
         * @param {string} message - The user's prompt/command
         * @param {Token} [token] - Optional specific token, defaults to controlled token
         */
        async askAI(message, token = null) {
            // Get the controlled token or the specified one
            const selectedToken = token || canvas.tokens.controlled[0];

            if (!selectedToken) {
                ui.notifications.warn("Please select a token first");
                return;
            }

            // Collect abilities from the token's actor
            const abilities = collectTokenAbilities(selectedToken);

            // Build the payload
            const payload = {
                prompt: message,
                tokenId: selectedToken.id,
                tokenName: selectedToken.name,
                abilities: abilities,
                worldState: collectWorldState()
            };

            try {
                const res = await fetch("http://localhost:8080/gm/respond", {
                    method: "POST",
                    headers: {"Content-Type": "application/json"},
                    body: JSON.stringify(payload)
                });

                if (!res.ok) {
                    throw new Error(`Server responded with ${res.status}`);
                }

                const data = await res.json();
                await handleAiResponse(data);
            } catch (error) {
                console.error("AI GM Error:", error);
                ui.notifications.error(`AI GM Error: ${error.message}`);
            }
        },

        /**
         * Open the character generator UI
         */
        openCharacterGenerator() {
            new CharacterGeneratorUI().render(true);
        },

        /**
         * Open the item generator UI
         */
        openItemGenerator() {
            new ItemGeneratorUI().render(true);
        },

        /**
         * Generate a character from a prompt (programmatic API)
         * @param {string} prompt - Character description
         * @param {string} actorType - Actor type to generate
         * @param {string} language - Language for generation
         * @param {Array<string>} selectedFields - Optional: specific fields to generate
         */
        async generateCharacter(prompt, actorType = 'character', language = 'en', selectedFields = null) {
            const blueprint = blueprintGenerator.generateAIBlueprint(actorType, selectedFields);

            try {
                const response = await fetch('http://localhost:8080/gm/character/generate', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        prompt: prompt,
                        actorType: actorType,
                        blueprint: blueprint,
                        language: language
                    })
                });

                if (!response.ok) {
                    throw new Error(`Server responded with ${response.status}`);
                }

                const data = await response.json();
                return CharacterAPI.createCharacter(data.character);

            } catch (error) {
                console.error('Character generation error:', error);
                throw error;
            }
        }
    };

    // Log system information
    console.log(`[AI-GM] System: ${game.system.id} (${game.system.title})`);
    console.log(`[AI-GM] Available Actor Types:`, blueprintGenerator.schemaExtractor.getActorTypes());
});

/**
 * Collect all abilities/items from a token's actor
 * @param {Token} token - The token to collect abilities from
 * @returns {Array} Array of ability objects
 */
function collectTokenAbilities(token) {
    const actor = token.actor;
    if (!actor) return [];

    const abilities = [];

    // Collect items (spells, features, weapons, etc.)
    for (const item of actor.items) {
        const ability = {
            id: item.id,
            name: item.name,
            type: item.type,
            description: stripHtml(item.system?.description?.value || item.system?.description || ""),
            actionType: item.system?.actionType || null,
            damage: item.system?.damage?.parts?.map(p => ({
                formula: p[0],
                type: p[1]
            })) || [],
            range: item.system?.range || null,
            uses: item.system?.uses ? {
                value: item.system.uses.value,
                max: item.system.uses.max,
                per: item.system.uses.per
            } : null,
            level: item.system?.level || null
        };
        abilities.push(ability);
    }

    // Include core ability scores (D&D style)
    if (actor.system?.abilities) {
        for (const [key, value] of Object.entries(actor.system.abilities)) {
            abilities.push({
                id: `ability-${key}`,
                name: key.toUpperCase(),
                type: "ability-score",
                value: value.value,
                mod: value.mod
            });
        }
    }

    // Include skills
    if (actor.system?.skills) {
        for (const [key, value] of Object.entries(actor.system.skills)) {
            abilities.push({
                id: `skill-${key}`,
                name: key,
                type: "skill",
                value: value.total || value.value,
                proficient: value.proficient
            });
        }
    }

    return abilities;
}

/**
 * Strip HTML tags from a string
 */
function stripHtml(html) {
    if (!html) return "";
    const tmp = document.createElement("DIV");
    tmp.innerHTML = html;
    return tmp.textContent || tmp.innerText || "";
}

/**
 * Collect relevant world state information
 * @returns {Object} World state object
 */
function collectWorldState() {
    const scene = game.scenes.active;

    return {
        sceneName: scene?.name || null,
        sceneId: scene?.id || null,
        tokens: scene ? canvas.tokens.placeables.map(t => ({
            id: t.id,
            name: t.name,
            x: t.x,
            y: t.y,
            actorId: t.actor?.id || null,
            hp: t.actor?.system?.attributes?.hp ? {
                value: t.actor.system.attributes.hp.value,
                max: t.actor.system.attributes.hp.max
            } : null,
            disposition: t.document?.disposition
        })) : [],
        combat: game.combat ? {
            round: game.combat.round,
            turn: game.combat.turn,
            currentCombatantId: game.combat.combatant?.tokenId
        } : null
    };
}

// Chat command handler
Hooks.on("chatMessage", (chatlog, message, chatdata) => {
    if (message.startsWith("/ai ")) {
        const query = message.substring(4);
        game.aiGM.askAI(query);
        return false; // Prevent message from appearing in chat
    }

    if (message.startsWith("/aichar ")) {
        const prompt = message.substring(8);
        game.aiGM.generateCharacter(prompt).then(actor => {
            ui.notifications.info(`Created character: ${actor.name}`);
            actor.sheet.render(true);
        }).catch(error => {
            ui.notifications.error(`Failed to generate character: ${error.message}`);
        });
        return false;
    }
});

// Add button to actor directory
Hooks.on("renderActorDirectory", (app, html, data) => {
    if (!game.user.isGM) return;

    const button = $(`
        <button class="ai-gm-generate-character" style="width: 100%; height: 30px; margin: 5px 0;">
            <i class="fas fa-magic"></i> AI Character Generator
        </button>
    `);

    button.click(() => {
        game.aiGM.openCharacterGenerator();
    });

    html.find('.directory-header').after(button);
});

// Add context menu option for character explanation
Hooks.on("getActorDirectoryEntryContext", (html, options) => {
    options.push({
        name: "Explain with AI",
        icon: '<i class="fas fa-book-open"></i>',
        condition: (li) => {
            const actor = game.actors.get(li.data("documentId"));
            return actor && game.user.isGM;
        },
        callback: async (li) => {
            const actor = game.actors.get(li.data("documentId"));
            if (!actor) return;

            try {
                const explanation = await game.aiGM.CharacterAPI.explainCharacter(actor);

                ChatMessage.create({
                    content: `<h3>${actor.name}</h3><p>${explanation}</p>`,
                    speaker: { alias: "AI Game Master" }
                });
            } catch (error) {
                ui.notifications.error(`Failed to explain character: ${error.message}`);
            }
        }
    });

    // Register a control button for item generation (optional)
    Hooks.on('getActorDirectoryEntryContext', (html, entries) => {
        entries.push({
            name: 'AI Item Generator',
            icon: '<i class="fas fa-hat-wizard"></i>',
            callback: () => game.aiGM.openItemGenerator()
        });
    });
});
