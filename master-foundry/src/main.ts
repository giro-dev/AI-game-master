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
import { TranscriptionManager } from './services/transcription-manager.js';
import { CorrectionTracker } from './services/correction-tracker.js';

const SERVER = 'http://localhost:8080';

/* ===================================================================== */
/*  Ready hook – bootstrap everything                                     */
/* ===================================================================== */

Hooks.on('ready', () => {
    console.log('[AI-GM] Initializing…');

    let blueprintGenerator: BlueprintGenerator | null = null;
    let wsClient: WebSocketClient | null = null;
    let snapshotSender: SystemSnapshotSender | null = null;
    let postProcessor: PostProcessingEngine | null = null;
    let panel: AIGameMasterPanel | null = null;
    let transcriptionManager: TranscriptionManager | null = null;
    let correctionTracker: CorrectionTracker | null = null;

    try {
        // Register settings (safe to call multiple times in same session)
        if (!game.settings.settings.has('ai-gm.defaultItemPack')) {
            game.settings.register('ai-gm', 'defaultItemPack', {
                name: 'Default Item Pack',
                scope: 'world',
                config: false,
                type: String,
                default: ''
            });
        }
        if (!game.settings.settings.has('ai-gm.transcriptionEnabled')) {
            game.settings.register('ai-gm', 'transcriptionEnabled', {
                name: 'Enable Voice Transcription',
                hint: 'Record your speech while talking in the AV session and transcribe it via OpenAI Whisper.',
                scope: 'client',
                config: true,
                type: Boolean,
                default: false,
            });
        }
        if (!game.settings.settings.has('ai-gm.transcriptionToChat')) {
            game.settings.register('ai-gm', 'transcriptionToChat', {
                name: 'Post Transcriptions to Chat',
                hint: 'When enabled, each transcription result is posted as a chat message.',
                scope: 'client',
                config: true,
                type: Boolean,
                default: false,
            });
        }
    } catch (e) {
        console.warn('[AI-GM] Settings registration failed (may already exist):', e);
    }

    try {
        blueprintGenerator = new BlueprintGenerator();
    } catch (e) {
        console.error('[AI-GM] BlueprintGenerator init failed:', e);
    }

    try {
        wsClient = new WebSocketClient(SERVER);
    } catch (e) {
        console.error('[AI-GM] WebSocketClient init failed:', e);
    }

    try {
        snapshotSender = new SystemSnapshotSender(SERVER);
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
        panel,
        transcriptionManager,
        correctionTracker,
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

        // ── Transcription result handling (WS path) ──
        (wsClient as any).on?.('onTranscriptionCompleted', (data: any) => {
            if (data?.text) {
                console.debug('[AI-GM] Transcription (WS):', data.text);
                if (game.settings?.get('ai-gm', 'transcriptionToChat') === true) {
                    const speaker = data.speaker;
                    ChatMessage.create({
                        content: `<em>[${speaker?.userName ?? 'Unknown'}]</em> ${data.text}`,
                        speaker: { alias: speaker?.characterName ?? speaker?.userName ?? 'Unknown' },
                    });
                }
            }
        });

        wsClient.connect().catch((err: any) => {
            console.warn('[AI-GM] WebSocket unavailable:', err.message ?? err);
        });
    }

    // ── Correction tracker (feedback loop for AI-generated actors) ──
    try {
        const sessionId: string = wsClient?.getSessionId() ?? `foundry-${game.user?.id ?? 'unknown'}-${Date.now()}`;
        correctionTracker = new CorrectionTracker(SERVER, sessionId);
        correctionTracker.start();
        if (game.aiGM) game.aiGM.correctionTracker = correctionTracker;
    } catch (e) {
        console.error('[AI-GM] CorrectionTracker init failed:', e);
    }

    // ── Transcription manager (voice → text) ──
    if (game.settings?.get('ai-gm', 'transcriptionEnabled') === true) {
        try {
            const sessionId: string = wsClient?.getSessionId() ?? `foundry-${game.user?.id ?? 'unknown'}-${Date.now()}`;
            transcriptionManager = new TranscriptionManager(SERVER, sessionId);

            transcriptionManager.onTranscribed((result) => {
                // Forward to the AI GM panel's session chat if open
                if (result.text && game.aiGM?.panel) {
                    (game.aiGM.panel as any)._appendTranscript?.(result);
                }
            });

            transcriptionManager.start().catch((e: any) => {
                console.warn('[AI-GM] TranscriptionManager failed to start:', e);
            });

            // Keep the reference available on game.aiGM after assignment
            if (game.aiGM) game.aiGM.transcriptionManager = transcriptionManager;
        } catch (e) {
            console.error('[AI-GM] TranscriptionManager init failed:', e);
        }
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
    if (!Array.isArray(controls)) return;

    const aiTool = {
        name: 'ai-gm-open',
        title: 'AI Game Master',
        icon: 'fas fa-hat-wizard',
        button: true,
        onClick: () => {
            try { game.aiGM?.open(); }
            catch (e) { console.error('[AI-GM] Failed to open panel:', e); }
        }
    };

    // Try adding to the token controls (works in v11 and v12)
    const tokenGroup = controls.find((c: any) => c.name === 'token' || c.name === 'tokens');
    if (tokenGroup?.tools) {
        // Avoid duplicates on re-render
        if (!tokenGroup.tools.find((t: any) => t.name === 'ai-gm-open')) {
            tokenGroup.tools.push(aiTool);
        }
        return;
    }

    // Fallback: add as a standalone control group
    if (!controls.find((c: any) => c.name === 'ai-gm')) {
        controls.push({
            name: 'ai-gm',
            title: 'AI Game Master',
            icon: 'fas fa-hat-wizard',
            visible: true,
            tools: [aiTool],
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

/* ===================================================================== */
/*  Actor context menu                                                    */
/* ===================================================================== */

Hooks.on('getActorDirectoryEntryContext', (_html: any, options: any[]) => {

    // ── Use as AI Reference Character ──
    options.push({
        name: 'AI: Use as Reference Character',
        icon: '<i class="fas fa-user-check"></i>',
        condition: (li: any) => {
            const actor = game.actors.get(li.data('documentId'));
            return actor && game.user.isGM;
        },
        callback: async (li: any) => {
            const actor = game.actors.get(li.data('documentId'));
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

                const res = await fetch(`${SERVER}/gm/character/reference`, {
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

