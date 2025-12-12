import { handleAiResponse } from "./api.js";

Hooks.on("ready", () => {
    console.log("AI GM module loaded");

    game.aiGM = {
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
        }
    };
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
});
