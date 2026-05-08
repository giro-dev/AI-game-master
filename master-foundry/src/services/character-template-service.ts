/**
 * Character Template Service
 * Manages saved character creation presets (templates/archetypes).
 *
 * Inspired by CoC7's "Setup" items and dnd5e's Advancement system,
 * templates store pre-configured creation parameters so users can
 * quickly create characters from known archetypes.
 *
 * @module CharacterTemplateService
 */

import type { CharacterTemplate } from '../types/index.js';

const SETTING_KEY = 'characterTemplates';

export class CharacterTemplateService {
    private templates: CharacterTemplate[] = [];
    private _ready = false;

    init(): void {
        this._registerSetting();
        this._load();
        this._ready = true;
        console.log(`[AI-GM Templates] Loaded ${this.templates.length} template(s)`);
    }

    private _registerSetting(): void {
        try {
            if (!game.settings.settings.has(`ai-gm.${SETTING_KEY}`)) {
                game.settings.register('ai-gm', SETTING_KEY, {
                    name: 'Character Templates',
                    scope: 'world',
                    config: false,
                    type: String,
                    default: '[]'
                });
            }
        } catch (e) {
            console.warn('[AI-GM Templates] Setting registration failed:', e);
        }
    }

    private _load(): void {
        try {
            const raw = game.settings.get('ai-gm', SETTING_KEY) as string;
            this.templates = JSON.parse(raw || '[]');
        } catch (e) {
            console.warn('[AI-GM Templates] Failed to load templates:', e);
            this.templates = [];
        }
    }

    private async _save(): Promise<void> {
        try {
            await game.settings.set('ai-gm', SETTING_KEY, JSON.stringify(this.templates));
        } catch (e) {
            console.error('[AI-GM Templates] Failed to save templates:', e);
        }
    }

    getAll(): CharacterTemplate[] {
        return [...this.templates];
    }

    getByActorType(actorType: string): CharacterTemplate[] {
        return this.templates.filter(t => t.actorType === actorType);
    }

    getById(id: string): CharacterTemplate | undefined {
        return this.templates.find(t => t.id === id);
    }

    async save(template: CharacterTemplate): Promise<CharacterTemplate> {
        const existing = this.templates.findIndex(t => t.id === template.id);
        template.updatedAt = Date.now();

        if (existing >= 0) {
            this.templates[existing] = template;
        } else {
            template.createdAt = template.createdAt || Date.now();
            this.templates.push(template);
        }

        await this._save();
        return template;
    }

    async remove(id: string): Promise<boolean> {
        const idx = this.templates.findIndex(t => t.id === id);
        if (idx < 0) return false;
        this.templates.splice(idx, 1);
        await this._save();
        return true;
    }

    async duplicate(id: string): Promise<CharacterTemplate | null> {
        const source = this.getById(id);
        if (!source) return null;

        const copy: CharacterTemplate = {
            ...JSON.parse(JSON.stringify(source)),
            id: `tpl-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
            name: `${source.name} (Copy)`,
            createdAt: Date.now(),
            updatedAt: Date.now()
        };

        this.templates.push(copy);
        await this._save();
        return copy;
    }

    createNew(actorType: string): CharacterTemplate {
        return {
            id: `tpl-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
            name: '',
            description: '',
            actorType,
            promptText: '',
            selectedFields: [],
            language: 'en',
            itemTypes: [],
            tags: [],
            createdAt: Date.now(),
            updatedAt: Date.now()
        };
    }

    async importTemplates(json: string): Promise<number> {
        try {
            const imported: CharacterTemplate[] = JSON.parse(json);
            if (!Array.isArray(imported)) throw new Error('Expected array');

            let count = 0;
            for (const tpl of imported) {
                if (!tpl.id || !tpl.name || !tpl.actorType) continue;
                tpl.id = `tpl-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
                tpl.createdAt = Date.now();
                tpl.updatedAt = Date.now();
                this.templates.push(tpl);
                count++;
            }

            await this._save();
            return count;
        } catch (e) {
            console.error('[AI-GM Templates] Import failed:', e);
            return 0;
        }
    }

    exportTemplates(ids?: string[]): string {
        const toExport = ids
            ? this.templates.filter(t => ids.includes(t.id))
            : this.templates;
        return JSON.stringify(toExport, null, 2);
    }
}
