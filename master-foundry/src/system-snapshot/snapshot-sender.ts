/**
 * System Snapshot Sender
 *
 * Sends collected system snapshots to the master-server and manages
 * the system knowledge profile lifecycle (send on ready, re-send on version change).
 */

import { SystemSnapshotCollector } from './snapshot-collector.js';
import type { SystemProfile } from '../types/index.js';

export class SystemSnapshotSender {
    private readonly apiBaseUrl: string;
    private readonly collector: SystemSnapshotCollector;
    private _lastSentVersion: string | null = null;
    private _cachedProfile: SystemProfile | null = null;

    constructor(apiBaseUrl: string = 'http://localhost:8080') {
        this.apiBaseUrl = apiBaseUrl;
        this.collector = new SystemSnapshotCollector();
    }

    /**
     * Collect and send a system snapshot to the server.
     * Only re-sends if system version has changed or force=true.
     */
    async sendSnapshot(force: boolean = false): Promise<SystemProfile | null> {
        const currentVersion = `${game.system.id}@${game.system.version}`;

        if (!force && this._lastSentVersion === currentVersion && this._cachedProfile) {
            console.log('[AI-GM Snapshot] Using cached profile (version unchanged)');
            return this._cachedProfile;
        }

        try {
            console.log('[AI-GM Snapshot] Collecting and sending system snapshot...');
            const snapshot = await this.collector.collectSnapshot();

            const response = await fetch(`${this.apiBaseUrl}/api/system-profile/snapshot`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(snapshot)
            });

            if (!response.ok) {
                const errorText = await response.text();
                throw new Error(`Server responded with ${response.status}: ${errorText}`);
            }

            const profile: SystemProfile = await response.json();
            this._lastSentVersion = currentVersion;
            this._cachedProfile = profile;

            console.log(`[AI-GM Snapshot] System profile updated: ${profile.systemId} (${profile.fieldGroups?.length || 0} field groups)`);
            return profile;

        } catch (error) {
            console.error('[AI-GM Snapshot] Failed to send snapshot:', error);
            return null;
        }
    }

    /**
     * Get the current system profile from the server (without re-sending snapshot).
     */
    async getProfile(): Promise<SystemProfile | null> {
        if (this._cachedProfile) return this._cachedProfile;

        try {
            const response = await fetch(
                `${this.apiBaseUrl}/api/system-profile/${game.system.id}`,
                { method: 'GET', headers: { 'Content-Type': 'application/json' } }
            );

            if (response.ok) {
                this._cachedProfile = await response.json();
                return this._cachedProfile;
            }

            // Profile doesn't exist yet — send a snapshot
            return await this.sendSnapshot(true);
        } catch (error) {
            console.error('[AI-GM Snapshot] Failed to get profile:', error);
            return null;
        }
    }

    /**
     * Get the cached profile (no network call).
     */
    getCachedProfile(): SystemProfile | null {
        return this._cachedProfile;
    }

    /**
     * Clear cached data (e.g., on world change).
     */
    clearCache(): void {
        this._lastSentVersion = null;
        this._cachedProfile = null;
    }
}

