/**
 * AI Game Master — Module Settings
 *
 * Registers all Foundry VTT module settings and exposes typed helpers
 * to read their current values at runtime.
 */

export const MODULE_ID = 'ai-gm';
export const DEFAULT_SERVER_URL = 'http://localhost:8080';

/**
 * Foundry VTT user role constants (matches CONST.USER_ROLES).
 * 0 = NONE, 1 = PLAYER, 2 = TRUSTED, 3 = ASSISTANT, 4 = GAMEMASTER
 */
export const ROLE_NONE      = 0;
export const ROLE_PLAYER    = 1;
export const ROLE_TRUSTED   = 2;
export const ROLE_ASSISTANT = 3;
export const ROLE_GAMEMASTER = 4;

/**
 * All known RAG chunk types (must match backend classifier labels).
 * GM always sees everything; these control what non-GM roles can query.
 */
export const ALL_CHUNK_TYPES = [
    'rules',
    'character_creation',
    'item_definition',
    'spell_definition',
    'npc_stat_block',
    'bestiary',
    'example',
    'lore',
    'table',
    'other',
] as const;

export type ChunkType = typeof ALL_CHUNK_TYPES[number];

/** Human-readable labels for each chunk type. */
export const CHUNK_TYPE_LABELS: Record<ChunkType, string> = {
    rules:              'Rules',
    character_creation: 'Character Creation',
    item_definition:    'Items & Equipment',
    spell_definition:   'Spells',
    npc_stat_block:     'NPC Stat Blocks',
    bestiary:           'Bestiary / Lore',
    example:            'Examples',
    lore:               'World Lore',
    table:              'Tables',
    other:              'Other',
};

/**
 * Setting key storing RAG chunk-type access per role.
 * Value: JSON object { [role: number]: ChunkType[] }
 * GM (role 4) always gets all chunk types regardless of this setting.
 */
export const RAG_ROLES_SETTING = 'ragChunkTypesByRole';

/** Default: non-GM roles can search rules + lore + bestiary only. */
const DEFAULT_RAG_CHUNK_TYPES_BY_ROLE: Record<number, ChunkType[]> = {
    [ROLE_PLAYER]:    ['rules', 'lore', 'bestiary'],
    [ROLE_TRUSTED]:   ['rules', 'lore', 'bestiary', 'example', 'table'],
    [ROLE_ASSISTANT]: [...ALL_CHUNK_TYPES as unknown as ChunkType[]],
    [ROLE_GAMEMASTER]:[...ALL_CHUNK_TYPES as unknown as ChunkType[]],
};

/** All configurable features and the setting key used to store their allowed roles. */
export const FEATURE_ROLE_SETTINGS = {
    generator:    'rolesGenerator',
    chat:         'rolesChat',
    library:      'rolesLibrary',
    gameDirector: 'rolesGameDirector',
    transcription:'rolesTranscription',
} as const;

export type FeatureKey = keyof typeof FEATURE_ROLE_SETTINGS;

/** Default allowed roles for each feature (GM always has access, others default to GM-only). */
const DEFAULT_ROLES: Record<FeatureKey, number[]> = {
    generator:    [ROLE_GAMEMASTER],
    chat:         [ROLE_GAMEMASTER],
    library:      [ROLE_GAMEMASTER],
    gameDirector: [ROLE_GAMEMASTER],
    transcription:[ROLE_GAMEMASTER],
};

export function registerSettings(): void {
    game.settings.register(MODULE_ID, 'serverUrl', {
        name: 'Backend Server URL',
        hint: 'URL of the AI Game Master backend server (e.g. http://localhost:8080).',
        scope: 'world',
        config: true,
        type: String,
        default: DEFAULT_SERVER_URL
    });

    game.settings.register(MODULE_ID, 'enableTranscription', {
        name: 'Enable Voice Transcription',
        hint: 'Allow microphone capture and voice transcription during adventure sessions.',
        scope: 'world',
        config: true,
        type: Boolean,
        default: false
    });

    game.settings.register(MODULE_ID, 'enableGameDirector', {
        name: 'Enable Game Director',
        hint: 'Enable the AI Game Director for narrating adventures in real-time.',
        scope: 'world',
        config: true,
        type: Boolean,
        default: false
    });

    // Role-based access settings — stored as JSON arrays of role numbers.
    // GM (role 4) always has access regardless of these settings.
    for (const [feature, settingKey] of Object.entries(FEATURE_ROLE_SETTINGS) as [FeatureKey, string][]) {
        try {
            game.settings.register(MODULE_ID, settingKey, {
                name: `Allowed roles: ${feature}`,
                scope: 'world',
                config: false,
                type: String,
                default: JSON.stringify(DEFAULT_ROLES[feature])
            });
        } catch (_e) { /* already registered */ }
    }

    // RAG chunk-type access per role
    try {
        game.settings.register(MODULE_ID, RAG_ROLES_SETTING, {
            name: 'RAG chunk types by role',
            scope: 'world',
            config: false,
            type: String,
            default: JSON.stringify(DEFAULT_RAG_CHUNK_TYPES_BY_ROLE)
        });
    } catch (_e) { /* already registered */ }

    // Legacy setting kept for backward compatibility
    try {
        game.settings.register(MODULE_ID, 'defaultItemPack', {
            name: 'Default Item Pack',
            scope: 'world',
            config: false,
            type: String,
            default: ''
        });
    } catch (_e) { /* already registered */ }
}

export function getServerUrl(): string {
    try {
        return String(game.settings.get(MODULE_ID, 'serverUrl') || DEFAULT_SERVER_URL);
    } catch {
        return DEFAULT_SERVER_URL;
    }
}

export function isTranscriptionEnabled(): boolean {
    try {
        return Boolean(game.settings.get(MODULE_ID, 'enableTranscription'));
    } catch {
        return false;
    }
}

export function isGameDirectorEnabled(): boolean {
    try {
        return Boolean(game.settings.get(MODULE_ID, 'enableGameDirector'));
    } catch {
        return false;
    }
}

/**
 * Returns the list of role numbers that are allowed to access the given feature.
 * The GM role (4) is always included even if not stored explicitly.
 */
export function getAllowedRoles(feature: FeatureKey): number[] {
    try {
        const settingKey = FEATURE_ROLE_SETTINGS[feature];
        const raw = String(game.settings.get(MODULE_ID, settingKey) ?? '');
        const parsed: number[] = JSON.parse(raw);
        // Ensure GM always has access
        if (!parsed.includes(ROLE_GAMEMASTER)) parsed.push(ROLE_GAMEMASTER);
        return parsed;
    } catch {
        return [...DEFAULT_ROLES[feature]];
    }
}

/**
 * Saves the allowed role list for a feature.
 * GM role is always added automatically before saving.
 */
export async function setAllowedRoles(feature: FeatureKey, roles: number[]): Promise<void> {
    const settingKey = FEATURE_ROLE_SETTINGS[feature];
    // GM always has access — enforce it in storage too
    const toSave = [...new Set([...roles, ROLE_GAMEMASTER])];
    await game.settings.set(MODULE_ID, settingKey, JSON.stringify(toSave));
}

/**
 * Returns true if the current user is allowed to use the given feature.
 * GMs always return true.
 */
export function canUserAccessFeature(feature: FeatureKey): boolean {
    if (!game.user) return false;
    if (game.user.isGM) return true;
    const userRole: number = (game.user as any).role ?? ROLE_PLAYER;
    return getAllowedRoles(feature).includes(userRole);
}

// ─── RAG chunk-type access helpers ──────────────────────────────────────────

/**
 * Returns the full stored map of { role → ChunkType[] }.
 */
export function getRagChunkTypesByRole(): Record<number, ChunkType[]> {
    try {
        const raw = String(game.settings.get(MODULE_ID, RAG_ROLES_SETTING) ?? '');
        return JSON.parse(raw) as Record<number, ChunkType[]>;
    } catch {
        return { ...DEFAULT_RAG_CHUNK_TYPES_BY_ROLE };
    }
}

/**
 * Returns the chunk types allowed for a specific role number.
 * GM (role 4) always gets all chunk types.
 */
export function getAllowedChunkTypesForRole(role: number): ChunkType[] {
    if (role === ROLE_GAMEMASTER) return [...ALL_CHUNK_TYPES as unknown as ChunkType[]];
    const map = getRagChunkTypesByRole();
    return map[role] ?? [...(DEFAULT_RAG_CHUNK_TYPES_BY_ROLE[role] ?? ALL_CHUNK_TYPES as unknown as ChunkType[])];
}

/**
 * Returns the chunk types the current user is allowed to query.
 * GMs always get all chunk types.
 */
export function getAllowedChunkTypesForCurrentUser(): ChunkType[] {
    if (!game.user) return [...ALL_CHUNK_TYPES as unknown as ChunkType[]];
    if (game.user.isGM) return [...ALL_CHUNK_TYPES as unknown as ChunkType[]];
    const role: number = (game.user as any).role ?? ROLE_PLAYER;
    return getAllowedChunkTypesForRole(role);
}

/**
 * Saves the chunk-type access map for all roles.
 */
export async function setRagChunkTypesByRole(map: Record<number, ChunkType[]>): Promise<void> {
    // GM always has all types — enforce
    map[ROLE_GAMEMASTER] = [...ALL_CHUNK_TYPES as unknown as ChunkType[]];
    await game.settings.set(MODULE_ID, RAG_ROLES_SETTING, JSON.stringify(map));
}
