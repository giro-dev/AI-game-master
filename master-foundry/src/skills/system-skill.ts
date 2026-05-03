/**
 * System Skill — Declarative per-system adapter
 *
 * A "skill" is a JSON configuration that allows fine-tuning the module's
 * behaviour for a specific game system (or world) without writing code.
 *
 * Skills are **optional**: the core module is fully system-agnostic and works
 * via runtime schema introspection. Skills merely overlay/augment the
 * auto-detected information — they never replace the core pipeline.
 *
 * @module SystemSkill
 */

import type { FieldDefinition } from '../types/index.js';

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

/** A single constraint attached to a skill. */
export interface SkillConstraint {
    type: 'range' | 'point_budget' | 'required' | 'enum';
    fieldPath: string;
    description: string;
    parameters?: Record<string, any>;
}

/** Override / supplement for one actor type's schema. */
export interface ActorTypeOverride {
    /** Fields to add (merged with auto-detected ones). */
    addFields?: FieldDefinition[];

    /** Field paths to remove from the auto-detected schema. */
    removeFields?: string[];

    /**
     * Partial overrides keyed by field path.
     * Values are shallow-merged onto the matching FieldDefinition.
     */
    patchFields?: Record<string, Partial<FieldDefinition>>;
}

/**
 * The core skill definition.
 *
 * A skill MUST specify at least `systemId` and `name`.
 * Everything else is optional — include only what you need to fine-tune.
 */
export interface SystemSkill {
    /* ── Identity ── */
    /** Unique id (auto-generated if not provided). */
    id: string;
    /** Human-readable name. */
    name: string;
    /** Description of what this skill does. */
    description?: string;
    /** Author / source for credits. */
    author?: string;
    /** Semantic version (informational). */
    version?: string;

    /* ── Matching ── */
    /** Foundry system id to match (e.g. "CoC7", "custom-system-builder"). */
    systemId: string;
    /** Optional world id — useful for CSB-type systems where schema varies per world. */
    worldId?: string;
    /** Priority: higher wins when multiple skills match. Default 0. */
    priority?: number;
    /** Whether the skill is active. Default true. */
    enabled?: boolean;

    /* ── Actor overrides ── */
    /**
     * Extra actor types to add (if auto-detection misses some).
     * These are appended to the auto-detected list.
     */
    extraActorTypes?: string[];

    /**
     * Per-actor-type schema overrides.
     * Keys are actor type ids (e.g. "character", "npc").
     */
    actorOverrides?: Record<string, ActorTypeOverride>;

    /* ── Item overrides ── */
    /** Extra item types to add. */
    extraItemTypes?: string[];

    /** Per-item-type schema overrides (same shape as actorOverrides). */
    itemOverrides?: Record<string, ActorTypeOverride>;

    /* ── Constraints ── */
    /** Additional constraints for character validation. */
    constraints?: SkillConstraint[];

    /* ── AI hints ── */
    /**
     * Free-text instructions appended to the AI prompt.
     * Example: "In CoC7, characteristics are rolled with 3d6×5 for STR/CON/DEX/APP/POW,
     *           and 2d6+6 ×5 for SIZ/INT/EDU."
     */
    creationHints?: string;

    /**
     * Ordered steps for character creation (sent as context to the AI).
     */
    creationSteps?: string[];

    /**
     * Default items to create alongside a new actor.
     * Each entry is a minimal item template (name + type + optional system data).
     */
    defaultItems?: Array<{
        name: string;
        type: string;
        system?: Record<string, any>;
    }>;

    /**
     * Field path aliases: maps an alias → real field path.
     * Useful when the AI uses a common name but the system uses something different.
     * Example: { "strength": "system.characteristics.str.value" }
     */
    fieldAliases?: Record<string, string>;
}

/* ------------------------------------------------------------------ */
/*  Registry                                                           */
/* ------------------------------------------------------------------ */

const SETTING_KEY = 'systemSkills';

export class SystemSkillRegistry {
    private skills: SystemSkill[] = [];
    private _ready = false;

    /** Current system & world ids, set once on init. */
    private currentSystemId = '';
    private currentWorldId = '';

    /* ── Lifecycle ── */

    /**
     * Initialise the registry.
     * Call once during module ready hook, after game.system is available.
     */
    init(systemId: string, worldId: string): void {
        this.currentSystemId = systemId;
        this.currentWorldId = worldId;

        this._registerSetting();
        this._load();
        this._ready = true;

        const active = this.getActiveSkills();
        console.log(`[AI-GM Skills] Registry ready — ${this.skills.length} skill(s) loaded, ${active.length} active for ${systemId}/${worldId}`);
    }

    /* ── Queries ── */

    /** All registered skills (regardless of match). */
    getAllSkills(): SystemSkill[] {
        return [...this.skills];
    }

    /** Skills that match the current system (and optionally world), sorted by priority desc. */
    getActiveSkills(): SystemSkill[] {
        return this.skills
            .filter(s => this._matches(s))
            .sort((a, b) => (b.priority ?? 0) - (a.priority ?? 0));
    }

    /** Merged actor type overrides from all active skills (highest priority wins). */
    getMergedActorOverrides(actorType: string): ActorTypeOverride {
        const merged: ActorTypeOverride = { addFields: [], removeFields: [], patchFields: {} };

        // Process lowest priority first so highest priority wins on conflicts
        const active = this.getActiveSkills().reverse();
        for (const skill of active) {
            const ov = skill.actorOverrides?.[actorType];
            if (!ov) continue;
            if (ov.addFields) merged.addFields!.push(...ov.addFields);
            if (ov.removeFields) merged.removeFields!.push(...ov.removeFields);
            if (ov.patchFields) Object.assign(merged.patchFields!, ov.patchFields);
        }

        return merged;
    }

    /** Merged item type overrides from all active skills. */
    getMergedItemOverrides(itemType: string): ActorTypeOverride {
        const merged: ActorTypeOverride = { addFields: [], removeFields: [], patchFields: {} };

        const active = this.getActiveSkills().reverse();
        for (const skill of active) {
            const ov = skill.itemOverrides?.[itemType];
            if (!ov) continue;
            if (ov.addFields) merged.addFields!.push(...ov.addFields);
            if (ov.removeFields) merged.removeFields!.push(...ov.removeFields);
            if (ov.patchFields) Object.assign(merged.patchFields!, ov.patchFields);
        }

        return merged;
    }

    /** Collect extra actor types from all active skills. */
    getExtraActorTypes(): string[] {
        const extras = new Set<string>();
        for (const skill of this.getActiveSkills()) {
            for (const t of skill.extraActorTypes ?? []) extras.add(t);
        }
        return Array.from(extras);
    }

    /** Collect extra item types from all active skills. */
    getExtraItemTypes(): string[] {
        const extras = new Set<string>();
        for (const skill of this.getActiveSkills()) {
            for (const t of skill.extraItemTypes ?? []) extras.add(t);
        }
        return Array.from(extras);
    }

    /** Collect all constraints from active skills. */
    getAllConstraints(): SkillConstraint[] {
        const constraints: SkillConstraint[] = [];
        for (const skill of this.getActiveSkills()) {
            if (skill.constraints) constraints.push(...skill.constraints);
        }
        return constraints;
    }

    /** Collect merged creation hints from active skills. */
    getCreationHints(): string {
        const parts: string[] = [];
        for (const skill of this.getActiveSkills()) {
            if (skill.creationHints) parts.push(skill.creationHints);
        }
        return parts.join('\n\n');
    }

    /** Collect merged creation steps from active skills. */
    getCreationSteps(): string[] {
        for (const skill of this.getActiveSkills()) {
            if (skill.creationSteps?.length) return skill.creationSteps;
        }
        return [];
    }

    /** Collect default items from active skills. */
    getDefaultItems(): Array<{ name: string; type: string; system?: Record<string, any> }> {
        const items: Array<{ name: string; type: string; system?: Record<string, any> }> = [];
        for (const skill of this.getActiveSkills()) {
            if (skill.defaultItems) items.push(...skill.defaultItems);
        }
        return items;
    }

    /** Collect field aliases from active skills. */
    getFieldAliases(): Record<string, string> {
        const aliases: Record<string, string> = {};
        for (const skill of this.getActiveSkills()) {
            if (skill.fieldAliases) Object.assign(aliases, skill.fieldAliases);
        }
        return aliases;
    }

    /* ── Mutations ── */

    /** Add or update a skill. */
    upsert(skill: SystemSkill): void {
        if (!skill.id) {
            skill.id = this._generateId();
        }
        const idx = this.skills.findIndex(s => s.id === skill.id);
        if (idx >= 0) {
            this.skills[idx] = skill;
        } else {
            this.skills.push(skill);
        }
        this._save();
    }

    /** Remove a skill by id. */
    remove(skillId: string): boolean {
        const before = this.skills.length;
        this.skills = this.skills.filter(s => s.id !== skillId);
        if (this.skills.length !== before) {
            this._save();
            return true;
        }
        return false;
    }

    /** Toggle a skill's enabled state. */
    toggleEnabled(skillId: string): boolean {
        const skill = this.skills.find(s => s.id === skillId);
        if (!skill) return false;
        skill.enabled = !(skill.enabled ?? true);
        this._save();
        return true;
    }

    /** Import skills from a JSON string (array or single skill). */
    importFromJSON(json: string): number {
        const parsed = JSON.parse(json);
        const arr: SystemSkill[] = Array.isArray(parsed) ? parsed : [parsed];
        let count = 0;
        for (const raw of arr) {
            if (!raw.systemId || !raw.name) {
                console.warn('[AI-GM Skills] Skipping invalid skill (missing systemId or name):', raw);
                continue;
            }
            if (!raw.id) raw.id = this._generateId();
            this.upsert(raw);
            count++;
        }
        return count;
    }

    /** Export all skills as a JSON string. */
    exportToJSON(): string {
        return JSON.stringify(this.skills, null, 2);
    }

    /** Export only active skills as JSON string. */
    exportActiveToJSON(): string {
        return JSON.stringify(this.getActiveSkills(), null, 2);
    }

    /* ── Apply helpers (used by SchemaExtractor / BlueprintGenerator) ── */

    /**
     * Apply skill overrides to a field list (actor or item).
     * 1. Remove fields matching removeFields
     * 2. Patch fields matching patchFields
     * 3. Append addFields
     */
    applyFieldOverrides(fields: FieldDefinition[], overrides: ActorTypeOverride): FieldDefinition[] {
        let result = [...fields];

        // 1. Remove
        if (overrides.removeFields?.length) {
            const removeSet = new Set(overrides.removeFields);
            result = result.filter(f => !removeSet.has(f.path));
        }

        // 2. Patch
        if (overrides.patchFields) {
            for (const field of result) {
                const patch = overrides.patchFields[field.path];
                if (patch) {
                    Object.assign(field, patch);
                }
            }
        }

        // 3. Add (avoid duplicates)
        if (overrides.addFields?.length) {
            const existingPaths = new Set(result.map(f => f.path));
            for (const addField of overrides.addFields) {
                if (!existingPaths.has(addField.path)) {
                    result.push(addField);
                }
            }
        }

        return result;
    }

    /* ── Private helpers ── */

    private _matches(skill: SystemSkill): boolean {
        if (skill.enabled === false) return false;
        if (skill.systemId !== this.currentSystemId) return false;
        if (skill.worldId && skill.worldId !== this.currentWorldId) return false;
        return true;
    }

    private _registerSetting(): void {
        try {
            if (!game.settings.settings.has(`ai-gm.${SETTING_KEY}`)) {
                game.settings.register('ai-gm', SETTING_KEY, {
                    name: 'System Skills',
                    hint: 'Per-system adapter skills (JSON array)',
                    scope: 'world',
                    config: false,
                    type: String,
                    default: '[]'
                });
            }
        } catch (e) {
            console.warn('[AI-GM Skills] Setting registration failed:', e);
        }
    }

    private _load(): void {
        try {
            const raw = game.settings.get('ai-gm', SETTING_KEY) as string;
            this.skills = JSON.parse(raw || '[]');
        } catch (e) {
            console.warn('[AI-GM Skills] Failed to load skills:', e);
            this.skills = [];
        }
    }

    private _save(): void {
        try {
            game.settings.set('ai-gm', SETTING_KEY, JSON.stringify(this.skills));
        } catch (e) {
            console.error('[AI-GM Skills] Failed to save skills:', e);
        }
    }

    private _generateId(): string {
        return 'skill-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 7);
    }
}
