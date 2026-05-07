/**
 * AI Game Master — Foundry VTT Module Entry Point
 *
 * Initializes services, connects WebSocket, registers the unified panel,
 * and wires up Foundry hooks. All UI lives in AIGameMasterPanel.
 */

import { BlueprintGenerator } from './blueprints/blueprint-generator.js';
import { WebSocketClient } from './websocket-client.js';
import { SystemSnapshotSender } from './system-snapshot/snapshot-sender.js';
import { PostProcessingEngine } from './system-snapshot/post-processor.js';
import { AIGameMasterPanel } from './ui/ai-game-master-panel.js';
import { SystemSkillRegistry } from './skills/system-skill.js';
import { registerSettings, getServerUrl } from './settings.js';

/* ===================================================================== */
/*  Ready hook – bootstrap everything                                     */
/* ===================================================================== */

Hooks.on('init', () => {
    try {
        registerSettings();
    } catch (e) {
        console.warn('[AI-GM] Settings registration failed:', e);
    }
});

Hooks.on('ready', () => {
    console.log('[AI-GM] Initializing…');

    let blueprintGenerator: BlueprintGenerator | null = null;
    let wsClient: WebSocketClient | null = null;
    let snapshotSender: SystemSnapshotSender | null = null;
    let postProcessor: PostProcessingEngine | null = null;
    let panel: AIGameMasterPanel | null = null;
    let skillRegistry: SystemSkillRegistry | null = null;

    // ── Skill Registry — per-system declarative adapters ──
    try {
        skillRegistry = new SystemSkillRegistry();
        skillRegistry.init(game.system.id, game.world?.id ?? '');
    } catch (e) {
        console.error('[AI-GM] SkillRegistry init failed:', e);
    }

    try {
        blueprintGenerator = new BlueprintGenerator();
        if (skillRegistry) blueprintGenerator.setSkillRegistry(skillRegistry);
    } catch (e) {
        console.error('[AI-GM] BlueprintGenerator init failed:', e);
    }

    try {
        wsClient = new WebSocketClient(getServerUrl());
    } catch (e) {
        console.error('[AI-GM] WebSocketClient init failed:', e);
    }

    try {
        snapshotSender = new SystemSnapshotSender(getServerUrl());
    } catch (e) {
        console.error('[AI-GM] SnapshotSender init failed:', e);
    }

    postProcessor = new PostProcessingEngine();

    try {
        panel = new AIGameMasterPanel();
    } catch (e) {
        console.error('[AI-GM] Panel init failed:', e);
    }

    // ── Always expose on game.aiGM so the button never silently fails ──
    game.aiGM = {
        blueprintGenerator,
        wsClient,
        snapshotSender,
        postProcessor,
        skillRegistry,
        panel,
        open(): void {
            if (panel) {
                panel.render(true);
            } else {
                ui.notifications?.error('AI Game Master panel failed to initialize. Check the console.');
            }
        },
    };

    // ── WebSocket event handlers (item import, ingestion refresh) ──
    if (wsClient) {
        wsClient.on('onItemGenerationCompleted', async (event: any) => {
            const { items = [], packId } = event || {};
            if (!items.length) return ui.notifications.warn('No items returned.');
            const pack = packId ? game.packs.get(packId) : null;
            if (!pack) return ui.notifications.error(`Pack not found: ${packId}`);

            const ext = game.aiGM?.blueprintGenerator?.schemaExtractor;
            const validTypes: string[] = ext?.getItemTypes() ?? [];

            try {
                const created: string[] = [];
                for (const itemData of items) {
                    if (validTypes.length && !validTypes.includes(itemData.type)) {
                        const original = itemData.type;
                        itemData.type = validTypes[0];
                        console.warn(`[AI-GM] Mapped invalid item type "${original}" → "${itemData.type}"`);
                    }
                    const tmp = await Item.create(itemData, { temporary: true });
                    if (!tmp) {
                        console.warn(`[AI-GM] Item.create returned null for "${itemData.name}", skipping`);
                        continue;
                    }
                    const doc = await pack.importDocument(tmp);
                    created.push(doc.name);
                }
                if (created.length) {
                    ui.notifications.info(`Imported ${created.length} items to ${packId}`);
                } else {
                    ui.notifications.warn('No items could be imported.');
                }
            } catch (err: any) {
                console.error('[AI-GM] Item import failed:', err);
                ui.notifications.error(`Import failed: ${err.message}`);
            }
        });

        wsClient.on('onItemGenerationFailed', (msg: any) => {
            ui.notifications.error(`Item generation failed: ${msg?.error ?? 'unknown'}`);
        });

        wsClient.on('onIngestionCompleted', (data: any) => {
            console.log('[AI-GM] Ingestion completed:', data);
            ui.notifications.info(`Book ingested: ${data?.message ?? 'done'}`);
            snapshotSender?.sendSnapshot(true).then(profile => {
                if (profile && postProcessor) postProcessor.setProfile(profile);
            }).catch(() => {});
        });

        wsClient.on('onIngestionFailed', (data: any) => {
            console.error('[AI-GM] Ingestion failed:', data);
            ui.notifications.error(`Ingestion failed: ${data?.error ?? 'unknown'}`);
        });

        wsClient.connect().catch((err: any) => {
            console.warn('[AI-GM] WebSocket unavailable:', err.message ?? err);
        });
    }

    // ── System snapshot (learn the active system engine) ──
    if (snapshotSender) {
        snapshotSender.sendSnapshot().then(profile => {
            if (profile) {
                if (postProcessor) postProcessor.setProfile(profile);
                console.log(`[AI-GM] System profile: ${profile.systemId} — ${profile.fieldGroups?.length ?? 0} groups`);
            }
        }).catch((err: any) => {
            console.warn('[AI-GM] Snapshot failed:', err.message ?? err);
        });
    }

    console.log(`[AI-GM] Ready — ${game.system.id} (${game.system.title})`);
});

/* ===================================================================== */
/*  Scene Controls – single button to open the panel                      */
/* ===================================================================== */

Hooks.on('getSceneControlButtons', (controls: any) => {
    if (!game.user?.isGM) return;

    // v13+/v14: controls is a Record<string, SceneControl> (object with named keys)
    if (controls && typeof controls === 'object' && !Array.isArray(controls)) {
        if (!controls['ai-gm']) {
            controls['ai-gm'] = {
                name: 'ai-gm',
                title: 'AI Game Master',
                icon: 'fa-solid fa-hat-wizard',
                visible: game.user.isGM,
                tools: {
                    'ai-gm-open': {
                        name: 'ai-gm-open',
                        title: 'Open Panel',
                        icon: 'fa-solid fa-door-open',
                        button: true,
                        visible: game.user.isGM,
                        onChange: () => {
                            try { game.aiGM?.open(); }
                            catch (e) { console.error('[AI-GM] Failed to open panel:', e); }
                        }
                    }
                }
            };
        }
        return;
    }

    // v11/v12: controls is an Array
    if (!Array.isArray(controls)) return;

    if (!controls.find((c: any) => c.name === 'ai-gm')) {
        controls.push({
            name: 'ai-gm',
            title: 'AI Game Master',
            icon: 'fas fa-hat-wizard',
            visible: true,
            tools: [{
                name: 'ai-gm-open',
                title: 'Open Panel',
                icon: 'fas fa-door-open',
                button: true,
                onClick: () => {
                    try { game.aiGM?.open(); }
                    catch (e) { console.error('[AI-GM] Failed to open panel:', e); }
                }
            }],
            activeTool: 'ai-gm-open'
        });
    }
});

/* ===================================================================== */
/*  Chat commands                                                         */
/* ===================================================================== */

Hooks.on('chatMessage', (_chatlog: any, message: string) => {
    if (message.startsWith('/ai ')) {
        game.aiGM?.open();
        return false;
    }
    return true;
});

Hooks.on('createChatMessage', (message: any) => {
    try {
        void game.aiGM?.panel?.handleAdventureChatMessage?.(message);
    } catch (e) {
        console.warn('[AI-GM] Failed to process chat message for adventure rolls:', e);
    }
});

/* ===================================================================== */
/*  Actor context menu                                                    */
/* ===================================================================== */

Hooks.on('getActorDirectoryEntryContext', (_appOrHtml: any, options: any[]) => {

    // Helper to extract documentId from either jQuery (v11/v12) or HTMLElement (v13+/v14)
    const getDocId = (li: any): string | null => {
        if (typeof li.data === 'function') return li.data('documentId');          // jQuery
        if (li instanceof HTMLElement) return li.dataset.documentId ?? null;       // v14 HTMLElement
        return null;
    };

    // ── Use as AI Reference Character ──
    options.push({
        name: 'AI: Use as Reference Character',
        icon: '<i class="fas fa-user-check"></i>',
        condition: (li: any) => {
            const actor = game.actors.get(getDocId(li));
            return actor && game.user.isGM;
        },
        callback: async (li: any) => {
            const actor = game.actors.get(getDocId(li));
            if (!actor) return;

            try {
                ui.notifications?.info(`Capturing reference character "${actor.name}"…`);

                const payload = {
                    systemId: game.system.id,
                    actorType: actor.type,
                    label: actor.name,
                    actorData: actor.toObject(),
                    items: actor.items.map((i: any) => i.toObject()),
                };

                const res = await fetch(`${getServerUrl()}/gm/character/reference`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload),
                });

                if (!res.ok) throw new Error(`Server ${res.status}`);

                ui.notifications?.info(
                    `"${actor.name}" stored as AI reference for ${actor.type} (${game.system.id}). ` +
                    `Future AI-generated characters will replicate this character's structure.`
                );
                console.log('[AI-GM] Reference character stored:', actor.name, payload);
            } catch (e: any) {
                console.error('[AI-GM] Reference character capture failed:', e);
                ui.notifications?.error(`Failed to store reference character: ${e.message}`);
            }
        }
    });

    // ── Explain Character ──
    options.push({
        name: 'AI: Explain Character',
        icon: '<i class="fas fa-book-open"></i>',
        condition: (li: any) => {
            const actor = game.actors.get(getDocId(li));
            return actor && game.user.isGM;
        },
        callback: async (li: any) => {
            const actor = game.actors.get(getDocId(li));
            if (!actor) return;
            try {
                const res = await fetch(`${getServerUrl()}/gm/character/explain`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        characterData: {
                            actor: actor.toObject(),
                            items: actor.items.map((i: any) => i.toObject())
                        },
                        systemId: game.system.id
                    })
                });
                if (!res.ok) throw new Error(`Server ${res.status}`);
                const data = await res.json();
                ChatMessage.create({
                    content: `<h3>${actor.name}</h3><p>${data.explanation}</p>`,
                    speaker: { alias: 'AI Game Master' }
                });
            } catch (e: any) {
                ui.notifications.error(`Explain failed: ${e.message}`);
            }
        }
    });
});
