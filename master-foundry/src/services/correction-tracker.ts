/**
 * CorrectionTracker — feedback / correction loop for the AI Game Master module.
 *
 * How it works:
 * 1. When an AI-generated actor is created (via CharacterGenerationService), the caller
 *    should invoke `track(actorId, generatedSystemData, actorType)` so the original
 *    AI output is remembered in-memory.
 *
 * 2. A Foundry `updateActor` hook fires whenever any actor is saved.  For every tracked
 *    actor we extract the changed paths from Foundry's delta, then POST a correction to
 *    the server so SemanticMap confidence can be re-scored.
 *
 * 3. The server responds with a {@link CorrectionAck} indicating the new confidence and
 *    whether a full re-extraction has been scheduled.
 */

import type { CorrectionAck, CorrectionPayload } from '../types/index.js';

/** In-memory record of a single AI-generated actor. */
interface TrackedActor {
    actorType: string;
    generatedData: Record<string, unknown>;
}

export class CorrectionTracker {
    private readonly serverUrl: string;
    private readonly sessionId: string;

    /** actorId → original AI-generated system data */
    private readonly tracked = new Map<string, TrackedActor>();

    /** Foundry hook handle so we can deregister on stop() */
    private hookId: number | null = null;

    constructor(serverUrl: string, sessionId: string) {
        this.serverUrl = serverUrl;
        this.sessionId = sessionId;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    start(): void {
        this.hookId = Hooks.on('updateActor', this._onUpdateActor.bind(this));
        console.log('[CorrectionTracker] Started — watching updateActor hook');
    }

    stop(): void {
        if (this.hookId !== null) {
            Hooks.off('updateActor', this.hookId);
            this.hookId = null;
        }
        this.tracked.clear();
        console.log('[CorrectionTracker] Stopped');
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Register an AI-generated actor so edits to it will be tracked.
     *
     * Call this immediately after the actor is created in Foundry with the
     * exact `system` data that the AI returned.
     */
    track(actorId: string, generatedSystemData: Record<string, unknown>, actorType: string): void {
        this.tracked.set(actorId, { actorType, generatedData: generatedSystemData });
        console.debug(`[CorrectionTracker] Tracking actor ${actorId} (${actorType})`);
    }

    /** Stop tracking a specific actor (e.g. if it was deleted). */
    untrack(actorId: string): void {
        this.tracked.delete(actorId);
    }

    // ── Private ───────────────────────────────────────────────────────────

    private _onUpdateActor(actor: any, changes: any, _options: any, _userId: string): void {
        const tracked = this.tracked.get(actor.id);
        if (!tracked) return;

        const changedPaths = flattenPaths(changes);
        if (changedPaths.length === 0) return;

        const payload: CorrectionPayload = {
            systemId: game.system?.id ?? '',
            actorType: tracked.actorType,
            generatedData: tracked.generatedData,
            editedData: (actor.system ?? {}) as Record<string, unknown>,
            changedPaths,
            userId: game.user?.id ?? '',
            sessionId: this.sessionId,
            timestamp: Date.now(),
        };

        this._postCorrection(payload).catch(err => {
            console.warn('[CorrectionTracker] Failed to submit correction:', err);
        });
    }

    private async _postCorrection(payload: CorrectionPayload): Promise<void> {
        console.debug('[CorrectionTracker] Submitting correction for actor paths:', payload.changedPaths);

        const res = await fetch(`${this.serverUrl}/api/feedback/correction`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        });

        if (!res.ok) {
            console.warn(`[CorrectionTracker] Server returned ${res.status}`);
            return;
        }

        const ack: CorrectionAck = await res.json();
        console.log(
            `[CorrectionTracker] Correction acked: confidence=${(ack.newConfidence * 100).toFixed(0)}%` +
            (ack.reExtractionTriggered ? ' — re-extraction scheduled' : ''),
        );

        if (ack.reExtractionTriggered) {
            ui.notifications?.info(
                `[AI-GM] SemanticMap confidence dropped — re-learning ${payload.systemId} on next snapshot.`,
            );
        }
    }
}

// ── Utilities ────────────────────────────────────────────────────────────────

/**
 * Recursively flatten a nested Foundry change delta into dot-notation paths.
 *
 * Example input:  { system: { attributes: { hp: { value: 10 } } } }
 * Example output: ["system.attributes.hp.value"]
 */
function flattenPaths(obj: Record<string, unknown>, prefix = ''): string[] {
    const paths: string[] = [];
    for (const [key, value] of Object.entries(obj)) {
        const fullKey = prefix ? `${prefix}.${key}` : key;
        if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
            paths.push(...flattenPaths(value as Record<string, unknown>, fullKey));
        } else {
            paths.push(fullKey);
        }
    }
    return paths;
}
