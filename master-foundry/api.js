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
