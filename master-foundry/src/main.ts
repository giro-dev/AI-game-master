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

const SERVER = 'http://localhost:8080';

/* ===================================================================== */
/*  Ready hook – bootstrap everything                                     */
/* ===================================================================== */

Hooks.on('ready', () => {
    console.log('[AI-GM] Initializing…');

    // Register settings
    game.settings.register('ai-gm', 'defaultItemPack', {
        name: 'Default Item Pack',
        scope: 'world',
        config: false,
        type: String,
        default: ''
    });

    // ── Core services ──
    const blueprintGenerator = new BlueprintGenerator();
    const wsClient = new WebSocketClient(SERVER);
    const snapshotSender = new SystemSnapshotSender(SERVER);
    const postProcessor = new PostProcessingEngine();
    const panel = new AIGameMasterPanel();

    // ── Expose on game.aiGM ──
    game.aiGM = {
        blueprintGenerator,
        wsClient,
        snapshotSender,
        postProcessor,
        panel,

        /** Open the unified AI panel */
        open(): void { panel.render(true); },
    };

    // ── WebSocket event handlers (item import, ingestion refresh) ──

    wsClient.on('onItemGenerationCompleted', async (event: any) => {
        const { items = [], packId } = event || {};
        if (!items.length) return ui.notifications.warn('No items returned.');
        const pack = packId ? game.packs.get(packId) : null;
        if (!pack) return ui.notifications.error(`Pack not found: ${packId}`);

        // Get valid item types for the current system
        const ext = game.aiGM?.blueprintGenerator?.schemaExtractor;
        const validTypes: string[] = ext?.getItemTypes() ?? [];

        try {
            const created: string[] = [];
            for (const itemData of items) {
                // Validate/fix item type before creating
                if (validTypes.length && !validTypes.includes(itemData.type)) {
                    const original = itemData.type;
                    itemData.type = validTypes[0]; // fallback to first valid type
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
        // Refresh system profile with new knowledge
        snapshotSender.sendSnapshot(true).then(profile => {
            if (profile) postProcessor.setProfile(profile);
        }).catch(() => {});
    });

    wsClient.on('onIngestionFailed', (data: any) => {
        console.error('[AI-GM] Ingestion failed:', data);
        ui.notifications.error(`Ingestion failed: ${data?.error ?? 'unknown'}`);
    });

    // ── Connect WebSocket ──
    wsClient.connect().catch((err: any) => {
        console.warn('[AI-GM] WebSocket unavailable:', err.message ?? err);
    });

    // ── System snapshot (learn the active system engine) ──
    snapshotSender.sendSnapshot().then(profile => {
        if (profile) {
            postProcessor.setProfile(profile);
            console.log(`[AI-GM] System profile: ${profile.systemId} — ${profile.fieldGroups?.length ?? 0} groups`);
        }
    }).catch((err: any) => {
        console.warn('[AI-GM] Snapshot failed:', err.message ?? err);
    });

    console.log(`[AI-GM] Ready — ${game.system.id} (${game.system.title})`);
});

/* ===================================================================== */
/*  Scene Controls – single button to open the panel                      */
/* ===================================================================== */

Hooks.on('getSceneControlButtons', (controls: any) => {
    if (!game.user.isGM) return;

    controls.push({
        name: 'ai-gm',
        title: 'AI Game Master',
        icon: 'fas fa-hat-wizard',
        layer: 'controls',
        visible: true,
        tools: [
            {
                name: 'open-panel',
                title: 'Open AI Game Master Panel',
                icon: 'fas fa-hat-wizard',
                button: true,
                onClick: () => game.aiGM.open()
            }
        ]
    });
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

/* ===================================================================== */
/*  Actor context menu                                                    */
/* ===================================================================== */

Hooks.on('getActorDirectoryEntryContext', (_html: any, options: any[]) => {
    options.push({
        name: 'AI: Explain Character',
        icon: '<i class="fas fa-book-open"></i>',
        condition: (li: any) => {
            const actor = game.actors.get(li.data('documentId'));
            return actor && game.user.isGM;
        },
        callback: async (li: any) => {
            const actor = game.actors.get(li.data('documentId'));
            if (!actor) return;
            try {
                const res = await fetch(`${SERVER}/gm/character/explain`, {
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

