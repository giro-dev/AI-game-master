/**
 * SessionPanel — Chat / Session submodule.
 *
 * Encapsulates the AI chat interface and token context selection.
 */

import type { ChatEntry, VTTAction } from '../types/index.js';
import { getServerUrl } from '../settings.js';
import { escapeHtml, stripHtml, type PanelContext } from './panel-utils.js';

export class SessionPanel {

    // ── State ──
    private chatHistory: ChatEntry[] = [];
    private selectedTokenIds: Set<string> = new Set();
    private _controlTokenHookId: number | null = null;

    constructor(private readonly ctx: PanelContext) {}

    /* ------------------------------------------------------------------ */
    /*  getData contribution                                                */
    /* ------------------------------------------------------------------ */

    getData(): Partial<any> {
        return {
            chatHistory: this.chatHistory
        };
    }

    /* ------------------------------------------------------------------ */
    /*  activateListeners                                                   */
    /* ------------------------------------------------------------------ */

    activateListeners(html: any): void {
        html.find('[data-action="ask-ai"]').on('click', this._onAskAI.bind(this));
        html.find('#gm-session-prompt').on('keydown', (ev: any) => {
            if (ev.key === 'Enter' && !ev.shiftKey) { ev.preventDefault(); this._onAskAI(ev); }
        });

        // Token context selector
        this._refreshSelectedTokens(html);
        this._syncSelectedTokensFromCanvas({ onlyIfEmpty: true });
        this._refreshSceneTokenList(html);

        html.find('#gm-scene-tokens').on('change', '.scene-token-checkbox', this._onSceneTokenToggle.bind(this));
        html.find('[data-action="sync-tokens"]').on('click', () => {
            this._syncSelectedTokensFromCanvas({ overwrite: true });
            this._refreshSceneTokenList(html);
        });
        html.find('[data-action="clear-tokens"]').on('click', () => {
            this.selectedTokenIds.clear();
            this._refreshSceneTokenList(html);
        });

        // Refresh token pills when canvas selection changes
        if (this._controlTokenHookId !== null) Hooks.off('controlToken', this._controlTokenHookId);
        this._controlTokenHookId = Hooks.on('controlToken', () => {
            this._refreshSelectedTokens(html);
            if (this.selectedTokenIds.size === 0) {
                this._syncSelectedTokensFromCanvas({ onlyIfEmpty: true });
                this._refreshSceneTokenList(html);
            }
        });
    }

    close(): void {
        if (this._controlTokenHookId !== null) {
            Hooks.off('controlToken', this._controlTokenHookId);
            this._controlTokenHookId = null;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  AI chat                                                             */
    /* ------------------------------------------------------------------ */

    private async _onAskAI(_ev: any): Promise<void> {
        const html = this.ctx.element;
        const message: string = html.find('#gm-session-prompt').val()?.trim();
        if (!message) return;

        html.find('#gm-session-prompt').val('');
        this.chatHistory.push({ sender: 'You', text: message });
        this._rerenderChat(html);

        const includeTokens = html.find('#gm-include-tokens').is(':checked');
        const token = includeTokens ? this._getPrimarySelectedToken() : null;
        const payload = {
            prompt: message,
            tokenId: token?.id ?? null,
            tokenName: token?.name ?? null,
            worldId: game.world.id,
            foundrySystem: game.system.id,
            conversationId: `${game.world.id}-session`,
            abilities: token ? this._collectTokenAbilities(token) : [],
            worldState: this._collectWorldState()
        };

        try {
            const res = await fetch(`${getServerUrl()}/gm/respond`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            if (!res.ok) throw new Error(`Server ${res.status}`);
            const data = await res.json();

            if (data.narration) {
                this.chatHistory.push({ sender: 'AI Game Master', text: data.narration });
                ChatMessage.create({ content: data.narration, speaker: { alias: 'AI Game Master' } });
            }
            if (data.actions?.length) await this._executeActions(data.actions);

            this._rerenderChat(html);
        } catch (e: any) {
            this.chatHistory.push({ sender: 'System', text: `Error: ${e.message}` });
            this._rerenderChat(html);
        }
    }

    private _rerenderChat(html: any): void {
        const log = html.find('#gm-chat-log');
        if (this.chatHistory.length === 0) {
            log.html('<p class="empty-state">No messages yet.</p>');
            return;
        }
        log.html(this.chatHistory.map((m: ChatEntry) =>
            `<div class="chat-entry"><span class="chat-sender">${m.sender}</span><div class="chat-text">${m.text}</div></div>`
        ).join(''));
        log.scrollTop(log[0].scrollHeight);
    }

    /* ------------------------------------------------------------------ */
    /*  Token context                                                       */
    /* ------------------------------------------------------------------ */

    private _refreshSelectedTokens(html: any): void {
        const container = html.find('#gm-selected-tokens');
        if (!container.length) return;

        const controlled = canvas.tokens?.controlled ?? [];
        if (controlled.length === 0) {
            container.html('<span class="empty-state">No tokens selected on canvas</span>');
            return;
        }

        const pills = controlled.map((t: any) => {
            const img = t.document?.texture?.src || t.actor?.img || 'icons/svg/mystery-man.svg';
            const name = escapeHtml(t.name || 'Unknown');
            return `<span class="token-pill"><img src="${img}" alt="${name}"> ${name}</span>`;
        });
        container.html(pills.join(''));
    }

    private _syncSelectedTokensFromCanvas(options: { onlyIfEmpty?: boolean; overwrite?: boolean } = {}): void {
        const { onlyIfEmpty = false, overwrite = true } = options;
        if (onlyIfEmpty && this.selectedTokenIds.size > 0) return;

        const controlled = canvas.tokens?.controlled ?? [];
        if (controlled.length === 0) return;

        const next = new Set<string>(controlled.map((t: any) => String(t.id)));
        if (overwrite) this.selectedTokenIds = next;
        else for (const id of next) this.selectedTokenIds.add(id);
    }

    private _getPrimarySelectedToken(): any | null {
        const placeables = canvas.tokens?.placeables ?? [];
        for (const id of this.selectedTokenIds) {
            const t = placeables.find((p: any) => p.id === id);
            if (t) return t;
        }
        return canvas.tokens?.controlled?.[0] ?? null;
    }

    private _onSceneTokenToggle(ev: any): void {
        const tokenId: string | undefined = ev.currentTarget?.dataset?.tokenId;
        if (!tokenId) return;
        if (ev.currentTarget.checked) this.selectedTokenIds.add(tokenId);
        else this.selectedTokenIds.delete(tokenId);
    }

    private _refreshSceneTokenList(html: any): void {
        const container = html.find('#gm-scene-tokens');
        if (!container.length) return;

        const placeables = canvas.tokens?.placeables ?? [];
        if (!placeables.length) {
            container.html('<span class="empty-state">No tokens in this scene</span>');
            return;
        }

        const tokens = placeables
            .filter((t: any) => t?.document)
            .sort((a: any, b: any) => String(a.name ?? '').localeCompare(String(b.name ?? '')));

        const rows = tokens.map((t: any) => {
            const img = t.document?.texture?.src || t.actor?.img || 'icons/svg/mystery-man.svg';
            const name = escapeHtml(t.name || 'Unknown');
            const checked = this.selectedTokenIds.has(t.id) ? 'checked' : '';
            return `
              <label class="scene-token-item" title="${name}">
                <input type="checkbox" class="scene-token-checkbox" data-token-id="${t.id}" ${checked}>
                <img src="${img}" alt="${name}">
                <span class="scene-token-name">${name}</span>
              </label>
            `;
        });

        container.html(rows.join(''));
    }

    /* ------------------------------------------------------------------ */
    /*  World state helpers                                                 */
    /* ------------------------------------------------------------------ */

    _collectTokenAbilities(token: any): any[] {
        const actor = token.actor;
        if (!actor) return [];
        const abilities: any[] = [];

        for (const item of actor.items) {
            abilities.push({
                id: item.id, name: item.name, type: item.type,
                description: stripHtml(item.system?.description?.value || item.system?.description || ''),
                actionType: item.system?.actionType ?? null,
                damage: item.system?.damage?.parts?.map((p: any) => ({ formula: p[0], type: p[1] })) ?? [],
                range: item.system?.range ?? null,
                uses: item.system?.uses ? { value: item.system.uses.value, max: item.system.uses.max, per: item.system.uses.per } : null,
                level: item.system?.level ?? null
            });
        }
        if (actor.system?.abilities) {
            for (const [k, v] of Object.entries(actor.system.abilities)) {
                abilities.push({ id: `ability-${k}`, name: k.toUpperCase(), type: 'ability-score', value: (v as any).value, mod: (v as any).mod });
            }
        }
        if (actor.system?.skills) {
            for (const [k, v] of Object.entries(actor.system.skills)) {
                abilities.push({ id: `skill-${k}`, name: k, type: 'skill', value: (v as any).total ?? (v as any).value, proficient: (v as any).proficient });
            }
        }
        return abilities;
    }

    _collectWorldState(): any {
        const scene = game.scenes?.active;
        return {
            sceneName: scene?.name ?? null,
            sceneId: scene?.id ?? null,
            tokens: scene ? canvas.tokens.placeables.map((t: any) => ({
                id: t.id, name: t.name, x: t.x, y: t.y,
                actorId: t.actor?.id ?? null,
                hp: t.actor?.system?.attributes?.hp ? { value: t.actor.system.attributes.hp.value, max: t.actor.system.attributes.hp.max } : null,
                disposition: t.document?.disposition
            })) : [],
            combat: game.combat ? { round: game.combat.round, turn: game.combat.turn, currentCombatantId: game.combat.combatant?.tokenId } : null,
            lastRoll: this.ctx.getLastRoll()
        };
    }

    /* ------------------------------------------------------------------ */
    /*  VTT action executor (used by chat responses)                       */
    /* ------------------------------------------------------------------ */

    async _executeActions(actions: VTTAction[]): Promise<void> {
        for (const action of actions) {
            try {
                switch (action.type) {
                    case 'createToken': {
                        const scene = game.scenes.active;
                        await scene.createEmbeddedDocuments('Token', [{ name: action.name, img: action.img, x: action.x, y: action.y, actorId: action.actorId }]);
                        break;
                    }
                    case 'applyDamage': {
                        const actor = game.actors.get(action.target);
                        if (actor) await actor.applyDamage(action.amount);
                        break;
                    }
                    case 'moveToken': {
                        const tok = canvas.tokens.get(action.tokenId);
                        if (tok) await tok.document.update({ x: action.x, y: action.y });
                        break;
                    }
                    case 'useAbility': {
                        const tok = canvas.tokens.get(action.tokenId);
                        const item = tok?.actor?.items.get(action.abilityId);
                        if (item) await item.use({}, { event: null });
                        break;
                    }
                    case 'rollAbilityCheck': {
                        const tok = canvas.tokens.get(action.tokenId);
                        if (tok?.actor) await tok.actor.rollAbilityTest(action.ability);
                        break;
                    }
                    case 'rollSkillCheck': {
                        const tok = canvas.tokens.get(action.tokenId);
                        if (tok?.actor) await tok.actor.rollSkill(action.skill);
                        break;
                    }
                    case 'rollSavingThrow': {
                        const tok = canvas.tokens.get(action.tokenId);
                        if (tok?.actor) await tok.actor.rollAbilitySave(action.ability);
                        break;
                    }
                }
            } catch (e) {
                console.error(`[AI-GM SessionPanel] Action ${action.type} failed:`, e);
            }
        }
    }
}
