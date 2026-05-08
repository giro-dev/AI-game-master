/**
 * FeaturesPanel — Feature toggles + Adventure Director submodule.
 *
 * Manages the enableTranscription / enableGameDirector / serverUrl settings
 * and all Adventure Director state and logic (was the "Adventure" tab).
 */

import { AudioCaptureService } from '../services/audio-capture-service.js';
import { AudioPlaybackService } from '../services/audio-playback-service.js';
import type {
    AdventureSummary,
    AdventureSavedSessionSummary,
    AdventureNpcInfo,
    AdventureSceneInfo,
    AdventureRollInfo,
    AdventureStartResponse,
    NpcDialoguePayload,
    IntentConfirmationPayload,
    DirectorNarrationPayload,
    AdventureStateUpdatePayload,
    VTTAction
} from '../types/index.js';
import { getServerUrl, isTranscriptionEnabled, isGameDirectorEnabled, MODULE_ID, DEFAULT_SERVER_URL } from '../settings.js';
import { escapeHtml, stripHtml, type PanelContext } from './panel-utils.js';
import { SessionPanel } from './session-panel.js';

export class FeaturesPanel {

    // ── Adventure state ──
    private adventures: AdventureSummary[] = [];
    private adventureSavedSessions: AdventureSavedSessionSummary[] = [];
    private selectedAdventureId: string | null = null;
    private adventureSessionId: string | null = null;
    private adventureSessionName: string | null = null;
    private adventureSessionParticipants: string[] = [];
    private adventureSessionSummary: string | null = null;
    private adventureScene: AdventureSceneInfo | null = null;
    private discoveredClues: string[] = [];
    private metNpcs: string[] = [];
    private tensionLevel: number = 0;
    private adventureNpcs: AdventureNpcInfo[] = [];
    private adventureLog: string[] = [];
    private lastAdventureRoll: AdventureRollInfo | null = null;
    private processedAdventureRollMessageIds: Set<string> = new Set();
    private speakingNpc: NpcDialoguePayload | null = null;
    private isRecording: boolean = false;
    private isProcessing: boolean = false;
    private _audioCapture: AudioCaptureService | null = null;
    private _audioPlayback: AudioPlaybackService | null = null;
    private _adventureHandlersRegistered: boolean = false;

    // ── Injected session panel (for world-state collection during narration) ──
    private _sessionPanel: SessionPanel | null = null;

    constructor(private readonly ctx: PanelContext) {}

    /** Inject the session panel after construction (circular reference workaround). */
    setSessionPanel(sp: SessionPanel): void {
        this._sessionPanel = sp;
    }

    getLastAdventureRoll(): AdventureRollInfo | null {
        return this.lastAdventureRoll;
    }

    getAdventureSessionId(): string | null {
        return this.adventureSessionId;
    }

    /* ------------------------------------------------------------------ */
    /*  getData contribution                                                */
    /* ------------------------------------------------------------------ */

    async getData(): Promise<Partial<any>> {
        const enableGameDirector = isGameDirectorEnabled();
        const enableTranscription = isTranscriptionEnabled();

        if (enableGameDirector) {
            await this._refreshAdventures();
            await this._refreshAdventureSessions();
        }

        return {
            // Feature flags
            enableTranscription,
            enableGameDirector,
            serverUrl: getServerUrl(),

            // Adventure data (only relevant when game director is enabled)
            adventures: this.adventures.map(a => ({ ...a, selected: a.id === this.selectedAdventureId })),
            adventureSavedSessions: this.adventureSavedSessions.map(session => ({
                ...session,
                participantNamesText: (session.participantNames ?? []).join(', '),
                updatedAtLabel: this._formatAdventureDate(session.updatedAt),
                createdAtLabel: this._formatAdventureDate(session.createdAt)
            })),
            adventureSelected: !!this.selectedAdventureId,
            adventureSessionActive: !!this.adventureSessionId,
            adventureSessionId: this.adventureSessionId,
            adventureSessionName: this.adventureSessionName,
            adventureSessionParticipants: this.adventureSessionParticipants,
            adventureSessionSummary: this.adventureSessionSummary,
            adventureScene: this.adventureScene,
            discoveredClues: this.discoveredClues,
            metNpcs: this.metNpcs,
            tensionLevel: this.tensionLevel,
            tensionMax: 10,
            tensionPct: Math.max(0, Math.min(100, Math.round((this.tensionLevel / 10) * 100))),
            adventureNpcs: this.adventureNpcs,
            adventureLog: this.adventureLog,
            speakingNpc: this.speakingNpc,
            isRecording: this.isRecording,
            isProcessing: this.isProcessing
        };
    }

    /* ------------------------------------------------------------------ */
    /*  activateListeners                                                   */
    /* ------------------------------------------------------------------ */

    activateListeners(html: any): void {
        // Feature toggles
        html.find('#ai-gm-enable-transcription').on('change', async (ev: any) => {
            await game.settings.set(MODULE_ID, 'enableTranscription', ev.target.checked);
            this.ctx.render(false);
        });
        html.find('#ai-gm-enable-transcription-quick').on('change', async (ev: any) => {
            await game.settings.set(MODULE_ID, 'enableTranscription', ev.target.checked);
            this.ctx.render(false);
        });
        html.find('#ai-gm-enable-game-director').on('change', async (ev: any) => {
            await game.settings.set(MODULE_ID, 'enableGameDirector', ev.target.checked);
            this.ctx.render(false);
        });
        html.find('#ai-gm-enable-game-director-quick').on('change', async (ev: any) => {
            await game.settings.set(MODULE_ID, 'enableGameDirector', ev.target.checked);
            this.ctx.render(false);
        });
        html.find('[data-action="save-server-url"]').on('click', async () => {
            const url = String(html.find('#ai-gm-server-url').val() || '').trim() || DEFAULT_SERVER_URL;
            await game.settings.set(MODULE_ID, 'serverUrl', url);
            ui.notifications?.info('Server URL saved.');
            this.ctx.render(false);
        });

        // Adventure controls
        html.find('#adventure-select').on('change', this._onAdventureSelectChange.bind(this));
        html.find('[data-action="upload-adventure"]').on('click', this._onUploadAdventure.bind(this));
        html.find('[data-action="start-adventure"]').on('click', this._onStartAdventure.bind(this));
        html.find('[data-action="resume-adventure"]').on('click', this._onResumeAdventure.bind(this));
        html.find('[data-action="toggle-mic"]').on('click', this._onToggleMic.bind(this));
        html.find('[data-action="end-adventure"]').on('click', this._onEndAdventure.bind(this));
        html.find('[data-action="send-adventure-text"]').on('click', this._onSendAdventureText.bind(this));
        html.find('#adventure-text-input').on('keydown', (ev: any) => { if (ev.key === 'Enter') this._onSendAdventureText(ev); });
        html.find('[data-action="create-adventure-npc"]').on('click', this._onCreateAdventureNpc.bind(this));

        this._registerAdventureWsHandlers();
    }

    /* ------------------------------------------------------------------ */
    /*  Adventure Director — public entry point                            */
    /* ------------------------------------------------------------------ */

    async handleAdventureChatMessage(message: any): Promise<void> {
        if (!this.adventureSessionId || !message) return;

        const rollInfo = this._extractAdventureRollInfo(message);
        if (!rollInfo) return;
        if (rollInfo.messageId && this.processedAdventureRollMessageIds.has(rollInfo.messageId)) return;
        if (rollInfo.messageId) this.processedAdventureRollMessageIds.add(rollInfo.messageId);

        this.lastAdventureRoll = rollInfo;

        const summary = this._formatAdventureRollSummary(rollInfo);
        this.adventureLog.push(`🎲 ${summary}`);
        this._rebuildAdventureSessionSummary();

        const ws = game.aiGM?.wsClient;
        if (!ws || !this.adventureSessionId) {
            this.ctx.render(false);
            return;
        }

        const context = await this._promptAdventureRollContext(rollInfo);
        if (context === null) {
            this.adventureLog.push('⏸️ Interpretació de la tirada ajornada.');
            this._rebuildAdventureSessionSummary();
            this.ctx.render(false);
            return;
        }

        const contextInstruction = context.trim()
            ? ` Context addicional del jugador: ${context.trim()}.`
            : '';

        ws.sendTranscription({
            transcription: `[RESULTAT DE TIRADA] ${summary}.${contextInstruction} Resol la situació immediata segons el resultat de la tirada i les regles aplicables; no demanis una nova tirada si aquesta ja resol l'acció.`,
            adventureSessionId: this.adventureSessionId,
            worldId: game.world?.id ?? undefined,
            foundrySystem: game.system?.id ?? undefined,
            playerName: rollInfo.actorName ?? rollInfo.speakerAlias ?? game.user?.name ?? undefined,
            worldState: this._collectWorldState()
        });
        this.isProcessing = true;
        this.ctx.render(false);
    }

    /* ------------------------------------------------------------------ */
    /*  Adventure — refresh helpers                                        */
    /* ------------------------------------------------------------------ */

    private async _refreshAdventures(): Promise<void> {
        const worldId = game.world?.id;
        if (!worldId) { this.adventures = []; return; }
        try {
            const res = await fetch(`${getServerUrl()}/adventure/${encodeURIComponent(worldId)}`);
            if (res.ok) {
                const list = await res.json();
                this.adventures = (list || []) as AdventureSummary[];
                if (this.selectedAdventureId && !this.adventures.find(a => a.id === this.selectedAdventureId)) {
                    this.selectedAdventureId = null;
                }
            } else {
                this.adventures = [];
            }
        } catch (_e) {
            this.adventures = [];
        }
    }

    private async _refreshAdventureSessions(): Promise<void> {
        const worldId = game.world?.id;
        if (!worldId || !this.selectedAdventureId) { this.adventureSavedSessions = []; return; }
        try {
            const res = await fetch(
                `${getServerUrl()}/adventure/${encodeURIComponent(this.selectedAdventureId)}/sessions?worldId=${encodeURIComponent(worldId)}`
            );
            if (res.ok) {
                this.adventureSavedSessions = (await res.json() ?? []) as AdventureSavedSessionSummary[];
            } else {
                this.adventureSavedSessions = [];
            }
        } catch (_e) {
            this.adventureSavedSessions = [];
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Adventure — event handlers                                          */
    /* ------------------------------------------------------------------ */

    private _onAdventureSelectChange(ev: any): void {
        this.selectedAdventureId = ev.target?.value || null;
        this.ctx.render(false);
    }

    private async _onUploadAdventure(_ev: any): Promise<void> {
        const worldId = game.world?.id;
        if (!worldId) { ui.notifications.warn('No active world'); return; }

        const input = document.createElement('input');
        input.type = 'file';
        input.accept = 'application/pdf';
        input.style.display = 'none';
        document.body.appendChild(input);
        const cleanup = () => input.remove();

        input.onchange = async () => {
            const file = input.files?.[0];
            cleanup();
            if (!file) return;
            const fd = new FormData();
            fd.append('file', file);
            fd.append('foundrySystem', game.system?.id ?? '');
            fd.append('worldId', worldId);
            fd.append('bookTitle', file.name.replace(/\.pdf$/i, ''));

            ui.notifications.info(`Ingerint aventura "${file.name}"...`);
            try {
                const res = await fetch(`${getServerUrl()}/adventure/upload`, { method: 'POST', body: fd });
                if (!res.ok) {
                    const errText = await res.text();
                    ui.notifications.error(`Error ingerint aventura: ${errText}`);
                    return;
                }
                const adventure = await res.json() as AdventureSummary;
                ui.notifications.info(`Aventura "${adventure.title}" carregada`);
                await this._refreshAdventures();
                this.selectedAdventureId = adventure.id;
                this.ctx.render(false);
            } catch (e: any) {
                ui.notifications.error(`Error pujant aventura: ${e?.message ?? e}`);
            }
        };
        input.addEventListener('cancel', cleanup);
        input.click();
    }

    private async _onStartAdventure(_ev: any): Promise<void> {
        if (!this.selectedAdventureId) { ui.notifications.warn('Selecciona una aventura primer'); return; }
        try {
            const worldId = game.world?.id;
            const startUrl = `${getServerUrl()}/adventure/${encodeURIComponent(this.selectedAdventureId)}/start`
                + (worldId ? `?worldId=${encodeURIComponent(worldId)}` : '');
            const res = await fetch(startUrl, { method: 'POST' });
            if (!res.ok) { ui.notifications.error(`No s'ha pogut iniciar la sessió: ${await res.text()}`); return; }
            const data = await res.json() as AdventureStartResponse;
            this._loadAdventureSessionState(data);
            ui.notifications.info('Aventura iniciada!');
            this.ctx.render(false);

            setTimeout(() => {
                game.aiGM?.wsClient?.sendTranscription({
                    transcription: '[Inici de l\'aventura — narra la introducció de l\'escena actual]',
                    adventureSessionId: data.sessionId,
                    worldId: worldId ?? undefined,
                    foundrySystem: game.system?.id ?? undefined,
                    playerName: game.user?.name ?? undefined
                });
                this.isProcessing = true;
                this.ctx.render(false);
            }, 500);
        } catch (e: any) {
            ui.notifications.error(`Error iniciant l'aventura: ${e?.message ?? e}`);
        }
    }

    private async _onResumeAdventure(ev: any): Promise<void> {
        const sessionId = ev.currentTarget?.dataset?.sessionId;
        if (!sessionId) return;
        try {
            const res = await fetch(`${getServerUrl()}/adventure/session/${encodeURIComponent(sessionId)}/resume`, { method: 'POST' });
            if (!res.ok) { ui.notifications.error(`No s'ha pogut reprendre la sessió: ${await res.text()}`); return; }
            const data = await res.json() as AdventureStartResponse;
            this._loadAdventureSessionState(data);
            this.adventureLog = data.sessionSummary ? [`Resum de la sessió: ${data.sessionSummary}`] : ['Sessió represa.'];
            ui.notifications.info('Sessió represa!');
            this.ctx.render(false);
        } catch (e: any) {
            ui.notifications.error(`Error reprenent la sessió: ${e?.message ?? e}`);
        }
    }

    private _onEndAdventure(_ev: any): void {
        this._audioCapture?.cancel();
        this._audioPlayback?.stop();
        this._resetAdventureState();
        this.ctx.render(false);
    }

    private async _onToggleMic(_ev: any): Promise<void> {
        if (!this.adventureSessionId) { ui.notifications.warn('Inicia una sessió d\'aventura primer'); return; }
        if (!isTranscriptionEnabled()) { ui.notifications.warn('Voice transcription is not enabled in Features settings.'); return; }
        if (!this._audioCapture) this._initAdventureAudio();
        try {
            await this._audioCapture?.toggle();
        } catch (e: any) {
            ui.notifications.error(`Microphone error: ${e?.message ?? e}`);
        }
    }

    private _onSendAdventureText(_ev: any): void {
        if (!this.adventureSessionId) { ui.notifications.warn('Inicia una sessió d\'aventura primer'); return; }
        const inputEl = document.getElementById('adventure-text-input') as HTMLInputElement | null;
        const text = inputEl?.value?.trim();
        if (!text) return;
        inputEl!.value = '';

        const ws = game.aiGM?.wsClient;
        if (!ws) { ui.notifications.error('WebSocket no connectat'); return; }

        ws.sendTranscription({
            transcription: text,
            adventureSessionId: this.adventureSessionId,
            worldId: game.world?.id ?? undefined,
            foundrySystem: game.system?.id ?? undefined,
            playerName: game.user?.name ?? undefined,
            worldState: this._collectWorldState()
        });
        this.isProcessing = true;
        this.ctx.render(false);
    }

    private async _onCreateAdventureNpc(ev: any): Promise<void> {
        const npcId = ev.currentTarget?.dataset?.npcId;
        const npc = this.adventureNpcs.find(n => n.id === npcId);
        if (!npc) return;

        const ext = game.aiGM?.blueprintGenerator?.schemaExtractor;
        const actorTypes: string[] = ext?.getActorTypes() ?? ['character'];
        const actorType = actorTypes.includes('npc') ? 'npc' : actorTypes[0];

        const promptParts = [`Crea un NPC anomenat "${npc.name}" per a una aventura de rol.`];
        if (npc.personality) promptParts.push(`Personalitat: ${npc.personality}`);
        if (npc.objectives) promptParts.push(`Objectius: ${npc.objectives}`);
        if (npc.currentDisposition) promptParts.push(`Disposició actual: ${npc.currentDisposition}`);
        const prompt = promptParts.join('\n');

        ui.notifications.info(`Generant NPC "${npc.name}"…`);

        try {
            let blueprint = game.aiGM.blueprintGenerator.generateAIBlueprint(actorType);
            if (game.aiGM.postProcessor) blueprint = game.aiGM.postProcessor.enhanceBlueprint(blueprint);

            const { CharacterGenerationService } = await import('../services/character-generation-service.js');
            const { CharacterDataSanitizer } = await import('../utils/character-data-sanitizer.js');
            const charService = new CharacterGenerationService(getServerUrl);
            const sessionId: string | null = game.aiGM?.wsClient?.getSessionId() ?? null;
            const request = charService.buildRequest({ prompt, actorType, blueprint, language: 'ca', sessionId });
            const charData = await charService.generateCharacter(request);
            const sanitized = CharacterDataSanitizer.sanitize(charData);
            if (!sanitized) throw new Error('Character generation returned empty data');

            const actorName = sanitized.actor?.name || npc.name;
            const aiSystemData = sanitized.actor?.system ?? {};

            const minimalActor = await (Actor as any).create({
                name: actorName, type: actorType,
                img: sanitized.actor?.img || 'icons/svg/mystery-man.svg'
            });
            if (!minimalActor) throw new Error('Actor creation failed');

            if (Object.keys(aiSystemData).length > 0) {
                const existingSystem = (minimalActor as any).system ?? {};
                const flatUpdate: Record<string, any> = {};
                this._flattenStructureAware(aiSystemData, 'system', flatUpdate, existingSystem);
                await minimalActor.update(flatUpdate);
            }

            if (sanitized.items?.length) {
                await minimalActor.createEmbeddedDocuments('Item', sanitized.items);
            }

            ui.notifications.info(`NPC "${npc.name}" creat com a actor!`);
            minimalActor.sheet.render(true);
        } catch (e: any) {
            console.error('[AI-GM FeaturesPanel] Adventure NPC creation failed:', e);
            ui.notifications.error(`Error creant NPC: ${e?.message ?? e}`);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Adventure — session state                                          */
    /* ------------------------------------------------------------------ */

    private _loadAdventureSessionState(data: AdventureStartResponse): void {
        this.adventureSessionId = data.sessionId;
        this.adventureSessionName = data.sessionName ?? null;
        this.adventureSessionParticipants = Array.isArray(data.participantNames) ? [...data.participantNames] : [];
        this.adventureSessionSummary = data.sessionSummary ?? null;
        this.adventureScene = data.currentScene ?? null;
        this.discoveredClues = Array.isArray(data.discoveredClues) ? [...data.discoveredClues] : [];
        this.metNpcs = Array.isArray(data.metNpcs) ? [...data.metNpcs] : [];
        this.tensionLevel = typeof data.tensionLevel === 'number' ? data.tensionLevel : 0;
        this.speakingNpc = null;
        this.isRecording = false;
        this.isProcessing = false;

        const adventureId = data.adventureId ?? this.selectedAdventureId;
        if (adventureId) this.selectedAdventureId = adventureId;
        const adv = this.adventures.find(a => a.id === adventureId);
        this.adventureNpcs = (adv?.npcs ?? []) as AdventureNpcInfo[];
        this.adventureLog = [];
        this.lastAdventureRoll = null;
        this.processedAdventureRollMessageIds.clear();
        this._rebuildAdventureSessionSummary();

        game.aiGM?.wsClient?.subscribeToAdventure(data.sessionId);
        this._initAdventureAudio();
    }

    private _resetAdventureState(): void {
        this.adventureSessionId = null;
        this.adventureSessionName = null;
        this.adventureSessionParticipants = [];
        this.adventureSessionSummary = null;
        this.adventureScene = null;
        this.discoveredClues = [];
        this.metNpcs = [];
        this.tensionLevel = 0;
        this.adventureNpcs = [];
        this.adventureLog = [];
        this.lastAdventureRoll = null;
        this.processedAdventureRollMessageIds.clear();
        this.speakingNpc = null;
        this.isRecording = false;
        this.isProcessing = false;
    }

    /* ------------------------------------------------------------------ */
    /*  Adventure — audio                                                   */
    /* ------------------------------------------------------------------ */

    private _initAdventureAudio(): void {
        if (!this.adventureSessionId) return;
        const ws = game.aiGM?.wsClient;
        if (!ws) return;

        this._audioCapture = new AudioCaptureService(ws, {
            adventureSessionId: this.adventureSessionId,
            worldId: game.world?.id ?? undefined,
            foundrySystem: game.system?.id ?? undefined,
            playerName: game.user?.name ?? undefined,
            worldState: () => this._collectWorldState(),
            onStateChange: (recording) => { this.isRecording = recording; this.ctx.render(false); },
            onSubmitting: () => { this.isProcessing = true; this.ctx.render(false); }
        });

        this._audioPlayback = new AudioPlaybackService({
            onSpeakingChange: (npc: NpcDialoguePayload | null) => { this.speakingNpc = npc; this.ctx.render(false); }
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Adventure — WebSocket handlers                                     */
    /* ------------------------------------------------------------------ */

    private _registerAdventureWsHandlers(): void {
        if (this._adventureHandlersRegistered) return;
        const ws = game.aiGM?.wsClient;
        if (!ws) return;

        ws.on('onConnected', (_data: any) => {
            if (this.adventureSessionId) ws.subscribeToAdventure(this.adventureSessionId);
        });

        ws.on('onTranscriptionReceived', (data: any) => {
            this.isProcessing = false;
            if (data?.transcription) {
                this.adventureLog.push(`Tu: ${data.transcription}`);
                this._rememberAdventureParticipant(game.user?.name ?? null);
                this._rebuildAdventureSessionSummary();
                this.ctx.render(false);
            }
        });

        ws.on('onDirectorNarration', (data: DirectorNarrationPayload) => {
            this.isProcessing = false;
            if (data?.narration) {
                this.adventureLog.push(`Director: ${data.narration}`);
                try {
                    (ChatMessage as any).create({
                        content: `<div class="ai-gm-narration" style="font-style:italic;">${data.narration}</div>`,
                        speaker: { alias: 'Director' }
                    });
                } catch (_e) { /* best effort */ }
                const narrationAudioSrc = data.narrationAudioUrl || data.narrationAudioBase64;
                if (narrationAudioSrc && this._audioPlayback) {
                    this._audioPlayback.enqueue({
                        npcId: 'director', npcName: 'Director', text: data.narration,
                        audioUrl: this._resolveAudioUrl(data.narrationAudioUrl),
                        audioBase64: data.narrationAudioBase64
                    });
                }
                this._rebuildAdventureSessionSummary();
                this.ctx.render(false);
            }
            if (Array.isArray(data?.actions) && data.actions.length) {
                this._applyVttActions(data.actions);
            }
        });

        ws.on('onDirectorAudioReady', (data: DirectorNarrationPayload) => {
            if (data?.narrationAudioUrl && this._audioPlayback) {
                this._audioPlayback.enqueue({
                    npcId: 'director', npcName: 'Director', text: data.narration,
                    audioUrl: this._resolveAudioUrl(data.narrationAudioUrl)
                });
            }
        });

        ws.on('onNpcDialogueAudio', (data: NpcDialoguePayload) => {
            const npcLabel = data?.npcName ?? data?.npcId ?? 'NPC';
            if (data?.text) {
                this.adventureLog.push(`${npcLabel}: ${data.text}`);
                try {
                    (ChatMessage as any).create({
                        content: `<strong>${escapeHtml(npcLabel)}</strong>: ${escapeHtml(data.text)}`,
                        speaker: { alias: npcLabel }
                    });
                } catch (_e) { /* best effort */ }
            }
            this._rebuildAdventureSessionSummary();
            if (data?.audioUrl || data?.audioBase64) {
                this._audioPlayback?.enqueue({ ...data, audioUrl: this._resolveAudioUrl(data.audioUrl) });
            } else {
                this.ctx.render(false);
            }
        });

        ws.on('onNpcAudioReady', (data: NpcDialoguePayload) => {
            if (data?.audioUrl || data?.audioBase64) {
                this._audioPlayback?.enqueue({ ...data, audioUrl: this._resolveAudioUrl(data.audioUrl) });
            }
        });

        ws.on('onIntentConfirmationRequest', (data: IntentConfirmationPayload) => {
            this.isProcessing = false;
            this._showIntentConfirmationDialog(data);
        });

        ws.on('onSceneTransition', (data: any) => {
            if (data?.title) this.adventureScene = { title: data.title, readAloudText: data.readAloudText ?? '' };
            this.adventureLog.push(`— Transició: ${data?.title ?? data?.sceneId ?? '?'} —`);
            this._rebuildAdventureSessionSummary();
            this.ctx.render(false);
        });

        ws.on('onAdventureStateUpdate', (data: AdventureStateUpdatePayload) => {
            if (Array.isArray(data?.discoveredClues)) {
                for (const c of data.discoveredClues) {
                    if (!this.discoveredClues.includes(c)) this.discoveredClues.push(c);
                }
            }
            if (data?.npcDispositionChanges) {
                for (const npcId of Object.keys(data.npcDispositionChanges)) {
                    if (!this.metNpcs.includes(npcId)) {
                        const npc = this.adventureNpcs.find(n => n.id === npcId);
                        this.metNpcs.push(npc?.name ?? npcId);
                    }
                }
            }
            if (typeof data?.tensionDelta === 'number') {
                this.tensionLevel = Math.max(0, Math.min(10, this.tensionLevel + data.tensionDelta));
            }
            this._rebuildAdventureSessionSummary();
            this.ctx.render(false);
        });

        this._adventureHandlersRegistered = true;
    }

    /* ------------------------------------------------------------------ */
    /*  Adventure — roll helpers                                           */
    /* ------------------------------------------------------------------ */

    private _extractAdventureRollInfo(message: any): AdventureRollInfo | null {
        const roll = Array.isArray(message?.rolls) && message.rolls.length > 0 ? message.rolls[0] : null;
        const isRoll = !!roll || !!message?.isRoll;
        if (!isRoll) return null;

        const actor = message?.actor ?? game.actors?.get?.(message?.speaker?.actor);
        const flavor = stripHtml(message?.flavor ?? '');
        const content = stripHtml(message?.content ?? '');
        const outcome = this._inferRollOutcome(message, content, flavor);
        const target = this._inferRollTarget(message, content, flavor);
        const total = typeof roll?.total === 'number' ? roll.total : null;
        const success = outcome === 'success' || outcome === 'critical-success'
            ? true : outcome === 'failure' || outcome === 'critical-failure' ? false : null;
        const margin = target != null && total != null ? total - target : null;

        return {
            messageId: message?.id ?? undefined,
            actorId: actor?.id ?? message?.speaker?.actor ?? undefined,
            actorName: actor?.name ?? undefined,
            speakerAlias: message?.speaker?.alias ?? undefined,
            tokenId: message?.speaker?.token ?? undefined,
            flavor: flavor || undefined,
            formula: roll?.formula ?? undefined,
            total: total ?? undefined,
            target, margin, outcome, success,
            content: content || undefined,
            dice: Array.isArray(roll?.dice)
                ? roll.dice.map((die: any) => ({
                    faces: die?.faces,
                    results: Array.isArray(die?.results)
                        ? die.results.map((r: any) => r?.result).filter((v: any) => typeof v === 'number')
                        : []
                }))
                : [],
            rolledAt: Date.now()
        };
    }

    private _inferRollOutcome(message: any, content: string, flavor: string): string | null {
        const candidates = [
            message?.flags?.dnd5e?.roll?.outcome,
            message?.flags?.system?.roll?.outcome,
            message?.flags?.coc7?.result?.outcome,
            message?.flags?.coc7?.check?.successLevel,
            content, flavor
        ].filter((v): v is string => typeof v === 'string' && v.trim().length > 0);

        for (const raw of candidates) {
            const text = raw.toLowerCase();
            if (/(critical success|èxit crític|exito crítico|extreme success|hard success)/.test(text)) return 'critical-success';
            if (/(critical failure|fumble|pifia|fracàs crític|fracaso crítico)/.test(text)) return 'critical-failure';
            if (/(success|èxit|exito|passed|supera)/.test(text)) return 'success';
            if (/(failure|failed|fracàs|fracaso|falla|miss)/.test(text)) return 'failure';
        }
        return null;
    }

    private _inferRollTarget(message: any, content: string, flavor: string): number | null {
        const explicitCandidates = [
            message?.flags?.dnd5e?.roll?.targetValue,
            message?.flags?.system?.roll?.targetValue,
            message?.flags?.coc7?.check?.difficulty,
            message?.flags?.coc7?.check?.rawValue
        ];
        for (const c of explicitCandidates) {
            if (typeof c === 'number' && Number.isFinite(c)) return c;
        }
        for (const text of [content, flavor]) {
            const match = text.match(/(?:vs\.?|contra|target|dificultat|difficulty|dc)\s*(\d{1,3})/i);
            if (match) return Number(match[1]);
        }
        return null;
    }

    private _formatAdventureRollSummary(roll: AdventureRollInfo): string {
        const who = roll.actorName ?? roll.speakerAlias ?? 'Algú';
        const what = roll.flavor || roll.formula || 'tirada';
        const total = typeof roll.total === 'number' ? ` = ${roll.total}` : '';
        const target = typeof roll.target === 'number' ? ` vs ${roll.target}` : '';
        const outcome = roll.outcome
            ? ({ 'critical-success': 'èxit crític', 'critical-failure': 'fracàs crític', success: 'èxit', failure: 'fracàs' } as Record<string, string>)[roll.outcome] ?? roll.outcome
            : (typeof roll.success === 'boolean' ? (roll.success ? 'èxit' : 'fracàs') : null);
        return `${who} ha fet ${what}${total}${target}${outcome ? ` (${outcome})` : ''}`;
    }

    private _promptAdventureRollContext(roll: AdventureRollInfo): Promise<string | null> {
        const summary = this._formatAdventureRollSummary(roll);

        return new Promise((resolve) => {
            let settled = false;
            const finish = (value: string | null): void => {
                if (settled) return;
                settled = true;
                resolve(value);
            };

            const dialog = new Dialog({
                title: 'Interpretar tirada',
                content: `
                    <p>${escapeHtml(summary)}</p>
                    <p class="hint">Vols afegir més context perquè el Director interpreti millor aquesta tirada?</p>
                    <div class="form-group">
                      <label for="ai-gm-roll-context">Context opcional</label>
                      <textarea id="ai-gm-roll-context" rows="4" placeholder="Ex.: què volia aconseguir el jugador..."></textarea>
                    </div>
                `,
                buttons: {
                    send: { icon: '<i class="fas fa-paper-plane"></i>', label: 'Enviar sense context', callback: () => finish('') },
                    sendWithContext: {
                        icon: '<i class="fas fa-comment-dots"></i>',
                        label: 'Afegir context i enviar',
                        callback: (html: any) => finish(String(html.find('#ai-gm-roll-context').val() ?? '').trim())
                    },
                    ignore: { icon: '<i class="fas fa-pause"></i>', label: 'Ara no', callback: () => finish(null) }
                },
                default: 'sendWithContext',
                close: () => finish(null)
            });
            dialog.render(true);
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Adventure — session summary                                        */
    /* ------------------------------------------------------------------ */

    private _rememberAdventureParticipant(playerName: string | null): void {
        if (!playerName) return;
        if (!this.adventureSessionParticipants.includes(playerName)) {
            this.adventureSessionParticipants.push(playerName);
        }
    }

    private _rebuildAdventureSessionSummary(): void {
        const parts: string[] = [];
        if (this.adventureScene?.title) parts.push(`Escena actual: ${this.adventureScene.title}.`);
        if (this.adventureSessionParticipants.length) parts.push(`Participants: ${this.adventureSessionParticipants.join(', ')}.`);
        if (this.discoveredClues.length) parts.push(`Pistes descobertes: ${this.discoveredClues.slice(0, 3).join(' · ')}${this.discoveredClues.length > 3 ? '…' : ''}.`);
        if (this.metNpcs.length) parts.push(`NPCs trobats: ${this.metNpcs.slice(0, 3).join(' · ')}${this.metNpcs.length > 3 ? '…' : ''}.`);
        const latestStoryBeat = [...this.adventureLog].reverse().find(line => !line.startsWith('Tu:'));
        if (latestStoryBeat) parts.push(`Últim desenvolupament: ${latestStoryBeat.slice(0, 220)}${latestStoryBeat.length > 220 ? '…' : ''}.`);
        this.adventureSessionSummary = parts.length ? parts.join(' ') : this.adventureSessionSummary;
    }

    private _formatAdventureDate(value?: string): string {
        if (!value) return '';
        try { return new Date(value).toLocaleString(); } catch (_e) { return value; }
    }

    /* ------------------------------------------------------------------ */
    /*  Adventure — VTT action helpers                                     */
    /* ------------------------------------------------------------------ */

    private _applyVttActions(actions: VTTAction[]): void {
        try { game.aiGM?.postProcessor?.applyActions?.(actions); } catch (e) {
            console.warn('[AI-GM FeaturesPanel] Failed to apply VTT actions:', e);
        }
        void this._executeActions(actions);
    }

    private async _executeActions(actions: VTTAction[]): Promise<void> {
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
                }
            } catch (e) {
                console.error(`[AI-GM FeaturesPanel] Action ${action.type} failed:`, e);
            }
        }
    }

    private _showIntentConfirmationDialog(data: IntentConfirmationPayload): void {
        if (!this.adventureSessionId) return;
        const sessionId = this.adventureSessionId;
        const ws = game.aiGM?.wsClient;
        if (!ws) return;

        const d = new Dialog({
            title: 'Confirmar intenció',
            content: `<p>${escapeHtml(data?.question ?? 'Vols continuar amb aquesta acció?')}</p>`,
            buttons: {
                yes: { icon: '<i class="fas fa-check"></i>', label: 'Sí', callback: () => ws.confirmIntent(true, sessionId) },
                no: { icon: '<i class="fas fa-times"></i>', label: 'No', callback: () => ws.confirmIntent(false, sessionId) },
                rephrase: { icon: '<i class="fas fa-comment"></i>', label: 'Reformular', callback: () => ws.confirmIntent('rephrase', sessionId) }
            },
            default: 'yes'
        });
        d.render(true);
    }

    /* ------------------------------------------------------------------ */
    /*  Shared helpers                                                      */
    /* ------------------------------------------------------------------ */

    private _resolveAudioUrl(url: string | undefined): string | undefined {
        if (!url) return undefined;
        if (url.startsWith('http://') || url.startsWith('https://')) return url;
        return `${getServerUrl()}${url}`;
    }

    private _collectWorldState(): any {
        return this._sessionPanel?.collectWorldState() ?? {
            sceneName: game.scenes?.active?.name ?? null,
            sceneId: game.scenes?.active?.id ?? null,
            tokens: [],
            combat: null,
            lastRoll: this.lastAdventureRoll
        };
    }

    private _flattenStructureAware(
        obj: Record<string, any>, prefix: string,
        result: Record<string, any>, template: any
    ): void {
        for (const [key, value] of Object.entries(obj)) {
            const path = prefix ? `${prefix}.${key}` : key;
            const tplValue = template?.[key];
            if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
                this._flattenStructureAware(value, path, result, tplValue ?? {});
            } else if (
                (typeof value === 'number' || typeof value === 'string' || typeof value === 'boolean') &&
                tplValue && typeof tplValue === 'object' && !Array.isArray(tplValue) && 'value' in tplValue
            ) {
                result[`${path}.value`] = value;
            } else {
                result[path] = value;
            }
        }
    }
}
