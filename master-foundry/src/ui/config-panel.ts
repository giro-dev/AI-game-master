/**
 * ConfigPanel — Configuration / System submodule.
 *
 * Manages system information, AI profile, reference character,
 * system skills, and WebSocket connection controls.
 */

import type { SystemSkill } from '../skills/system-skill.js';
import { getServerUrl } from '../settings.js';
import { escapeHtml, type PanelContext } from './panel-utils.js';

export class ConfigPanel {

    constructor(private readonly ctx: PanelContext) {}

    /* ------------------------------------------------------------------ */
    /*  getData contribution                                                */
    /* ------------------------------------------------------------------ */

    async getData(): Promise<Partial<any>> {
        // Fetch reference character for the selected actor type, unless an
        // adventure session is active (reduces noise during play).
        let referenceCharacter: any = null;
        if (!this.ctx.getAdventureSessionId()) {
            try {
                const refRes = await fetch(
                    `${getServerUrl()}/gm/character/reference/${encodeURIComponent(game.system.id)}/${encodeURIComponent(this.ctx.getSelectedActorType())}`
                );
                if (refRes.ok) {
                    const refData = await refRes.json();
                    referenceCharacter = {
                        label: refData.label,
                        actorType: refData.actorType,
                        itemCount: refData.items?.length ?? 0,
                        capturedAt: refData.capturedAt ? new Date(refData.capturedAt).toLocaleString() : null,
                    };
                }
            } catch (_e) { /* server unreachable */ }
        }

        const ext = game.aiGM?.blueprintGenerator?.schemaExtractor;
        let actorTypes: string[] = ['character'];
        let actorTypeLabels: Record<string, string> = {};
        let itemTypes: string[] = [];
        try {
            actorTypes = ext?.getActorTypes() ?? ['character'];
            actorTypeLabels = ext?.getActorTypeLabels() ?? {};
            itemTypes = ext?.getItemTypes() ?? [];
        } catch (_e) { /* not yet initialized */ }

        const skills = game.aiGM?.skillRegistry?.getAllSkills() ?? [];

        const availableReferenceActors = Array.from(game.actors ?? [])
            .map((actor: any) => ({
                id: actor.id,
                name: actor.name ?? 'Unnamed',
                type: actor.type ?? 'unknown',
                itemCount: actor.items?.size ?? 0
            }))
            .sort((a, b) => {
                const nameCmp = String(a.name).localeCompare(String(b.name));
                return nameCmp !== 0 ? nameCmp : String(a.type).localeCompare(String(b.type));
            });

        return {
            // System info
            systemId: game.system?.id ?? 'unknown',
            systemTitle: game.system?.title ?? 'Unknown System',
            foundryVersion: game.version ?? '',
            actorTypeNames: actorTypes,
            actorTypeLabels,
            itemTypeNames: itemTypes,

            // AI profile
            profile: (await game.aiGM?.snapshotSender?.getProfile()) ?? null,

            // Connection
            wsConnected: game.aiGM?.wsClient?.isConnected() ?? false,
            serverUrl: getServerUrl(),

            // Reference character
            referenceCharacter,
            availableReferenceActors,

            // Skills
            skills: skills.map((s: SystemSkill) => ({
                id: s.id,
                name: s.name,
                description: s.description ?? '',
                systemId: s.systemId,
                worldId: s.worldId ?? '',
                enabled: s.enabled !== false
            }))
        };
    }

    /* ------------------------------------------------------------------ */
    /*  activateListeners                                                   */
    /* ------------------------------------------------------------------ */

    activateListeners(html: any): void {
        // Connection
        html.find('[data-action="relearn-system"]').on('click', this._onRelearnSystem.bind(this));
        html.find('[data-action="reconnect-ws"]').on('click', this._onReconnectWS.bind(this));

        // Reference character
        html.find('[data-action="clear-reference"]').on('click', this._onClearReference.bind(this));
        html.find('[data-action="set-reference-character"]').on('click', this._onSetReferenceCharacter.bind(this));

        // Skills
        html.find('[data-action="import-skill"]').on('click', this._onImportSkill.bind(this));
        html.find('[data-action="create-skill"]').on('click', this._onCreateSkill.bind(this));
        html.find('[data-action="export-skills"]').on('click', this._onExportSkills.bind(this));
        html.find('[data-action="toggle-skill"]').on('click', this._onToggleSkill.bind(this));
        html.find('[data-action="edit-skill"]').on('click', this._onEditSkill.bind(this));
        html.find('[data-action="delete-skill"]').on('click', this._onDeleteSkill.bind(this));
    }

    /* ------------------------------------------------------------------ */
    /*  Connection                                                          */
    /* ------------------------------------------------------------------ */

    private async _onRelearnSystem(): Promise<void> {
        ui.notifications.info('Re-learning system…');
        try {
            const profile = await game.aiGM.snapshotSender.sendSnapshot(true);
            if (profile) game.aiGM.postProcessor.setProfile(profile);
            ui.notifications.info('System profile refreshed.');
            this.ctx.render(false);
        } catch (e: any) {
            ui.notifications.error(`Relearn failed: ${e.message}`);
        }
    }

    private async _onReconnectWS(): Promise<void> {
        try {
            await game.aiGM.wsClient.connect();
            ui.notifications.info('WebSocket reconnected.');
            if (this.ctx.getAdventureSessionId()) {
                game.aiGM.wsClient.subscribeToAdventure(this.ctx.getAdventureSessionId());
            }
            this.ctx.render(false);
        } catch (e: any) {
            ui.notifications.error(`Reconnect failed: ${e.message}`);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Reference character                                                 */
    /* ------------------------------------------------------------------ */

    private async _onClearReference(): Promise<void> {
        const systemId = game.system?.id;
        if (!systemId) return;
        try {
            const res = await fetch(
                `${getServerUrl()}/gm/character/reference/${encodeURIComponent(systemId)}/${encodeURIComponent(this.ctx.getSelectedActorType())}`,
                { method: 'DELETE' }
            );
            if (!res.ok) throw new Error(`Server ${res.status}`);
            ui.notifications.info('Reference character cleared.');
            this.ctx.render(false);
        } catch (e: any) {
            ui.notifications.error(`Clear failed: ${e.message}`);
        }
    }

    private async _onSetReferenceCharacter(): Promise<void> {
        const actorId = String(this.ctx.element.find('#ai-gm-reference-actor').val() ?? '');
        if (!actorId) { ui.notifications.warn('Selecciona un actor primer.'); return; }

        const actor = game.actors?.get(actorId);
        if (!actor) { ui.notifications.error('No s\'ha trobat l\'actor seleccionat.'); return; }

        await this.storeReferenceCharacter(actor);
        this.ctx.render(false);
    }

    /** Store a reference character (called by context menu hook in main.ts). */
    async storeReferenceCharacter(actor: any): Promise<void> {
        try {
            ui.notifications?.info(`Capturant "${actor.name}" com a reference character…`);

            const payload = {
                systemId: game.system.id,
                actorType: actor.type,
                label: actor.name,
                actorData: actor.toObject(),
                items: actor.items.map((item: any) => item.toObject())
            };

            const res = await fetch(`${getServerUrl()}/gm/character/reference`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            if (!res.ok) throw new Error(`Server ${res.status}`);
            ui.notifications?.info(`"${actor.name}" desat com a referència per ${actor.type} (${game.system.id}).`);
        } catch (e: any) {
            console.error('[AI-GM ConfigPanel] Reference character capture failed:', e);
            ui.notifications?.error(`No s'ha pogut desar la referència: ${e.message}`);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Skill management                                                    */
    /* ------------------------------------------------------------------ */

    private async _onImportSkill(_ev: any): Promise<void> {
        const registry = game.aiGM?.skillRegistry;
        if (!registry) { ui.notifications.error('Skill registry not available'); return; }

        const input = document.createElement('input');
        input.type = 'file';
        input.accept = '.json';
        input.onchange = async () => {
            const file = input.files?.[0];
            if (!file) return;
            try {
                const text = await file.text();
                const count = registry.importFromJSON(text);
                ui.notifications.info(`Imported ${count} skill(s)`);
                this.ctx.render(false);
            } catch (e: any) {
                ui.notifications.error(`Import failed: ${e.message}`);
            }
        };
        input.click();
    }

    private _onCreateSkill(_ev: any): void {
        const registry = game.aiGM?.skillRegistry;
        if (!registry) return;

        const template: SystemSkill = {
            id: '',
            name: 'My Custom Skill',
            description: 'Describe what this skill does',
            systemId: game.system.id,
            worldId: game.world?.id ?? '',
            priority: 0,
            enabled: true,
            extraActorTypes: [],
            actorOverrides: {},
            constraints: [],
            creationHints: '',
            creationSteps: [],
            defaultItems: [],
            fieldAliases: {}
        };

        this._openSkillEditor(template, (skill) => {
            registry.upsert(skill);
            ui.notifications.info(`Skill "${skill.name}" created`);
            this.ctx.render(false);
        });
    }

    private _onExportSkills(_ev: any): void {
        const registry = game.aiGM?.skillRegistry;
        if (!registry) return;

        const json = registry.exportToJSON();
        const blob = new Blob([json], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `ai-gm-skills-${game.system.id}.json`;
        a.click();
        URL.revokeObjectURL(url);
    }

    private _onToggleSkill(ev: any): void {
        const registry = game.aiGM?.skillRegistry;
        if (!registry) return;

        const skillId = ev.currentTarget?.dataset?.skillId;
        if (skillId && registry.toggleEnabled(skillId)) {
            this.ctx.render(false);
        }
    }

    private _onEditSkill(ev: any): void {
        const registry = game.aiGM?.skillRegistry;
        if (!registry) return;

        const skillId = ev.currentTarget?.dataset?.skillId;
        const skill = registry.getAllSkills().find((s: SystemSkill) => s.id === skillId);
        if (!skill) return;

        this._openSkillEditor(skill, (updated) => {
            registry.upsert(updated);
            ui.notifications.info(`Skill "${updated.name}" updated`);
            this.ctx.render(false);
        });
    }

    private async _onDeleteSkill(ev: any): Promise<void> {
        const registry = game.aiGM?.skillRegistry;
        if (!registry) return;

        const skillId = ev.currentTarget?.dataset?.skillId;
        if (!skillId) return;

        const confirmed = await Dialog.confirm({
            title: 'Delete Skill',
            content: '<p>Are you sure you want to delete this skill?</p>'
        });

        if (confirmed) {
            registry.remove(skillId);
            ui.notifications.info('Skill deleted');
            this.ctx.render(false);
        }
    }

    private _openSkillEditor(skill: SystemSkill, onSave: (s: SystemSkill) => void): void {
        const json = JSON.stringify(skill, null, 2);

        const d = new Dialog({
            title: skill.id ? `Edit Skill: ${skill.name}` : 'New Skill',
            content: `
                <div style="margin-bottom:8px;">
                    <p class="hint">Edit the skill JSON below. Required fields: <code>name</code>, <code>systemId</code>.</p>
                </div>
                <textarea id="skill-json-editor" style="width:100%;height:350px;font-family:monospace;font-size:0.85rem;tab-size:2;">${escapeHtml(json)}</textarea>
            `,
            buttons: {
                save: {
                    icon: '<i class="fas fa-save"></i>',
                    label: 'Save',
                    callback: (html: any) => {
                        const raw = html.find('#skill-json-editor').val() as string;
                        try {
                            const parsed = JSON.parse(raw) as SystemSkill;
                            if (!parsed.name || !parsed.systemId) {
                                ui.notifications.error('Skill must have a "name" and "systemId"');
                                return;
                            }
                            onSave(parsed);
                        } catch (e: any) {
                            ui.notifications.error(`Invalid JSON: ${e.message}`);
                        }
                    }
                },
                cancel: {
                    icon: '<i class="fas fa-times"></i>',
                    label: 'Cancel'
                }
            },
            default: 'save'
        }, { width: 560, height: 500 });

        d.render(true);
    }
}
