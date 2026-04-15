/**
 * Common type definitions for AI-GM module
 */

export interface FieldDefinition {
    path: string;
    type: string;
    label: string;
    required?: boolean;
    default?: any;
    min?: number;
    max?: number;
    choices?: any[];
}

export interface ActorData {
    name: string;
    type: string;
    img: string;
    system: Record<string, any>;
}

export interface ItemData {
    name: string;
    type: string;
    img?: string;
    system: Record<string, any>;
}

export interface CharacterData {
    actor: ActorData;
    items: ItemData[];
}

export interface CharacterBlueprint {
    systemId: string;
    actorType: string;
    actorFields: FieldDefinition[];
    availableItems: any[];
    constraints: string[];
    coreFields: FieldDefinition[];
    example: any;
}

export interface GenerationRequest {
    prompt: string;
    actorType: string;
    blueprint: CharacterBlueprint;
    language: string;
    sessionId?: string;
}

export interface ValidationError {
    field: string;
    message: string;
}

export interface ValidationResult {
    valid: boolean;
    errors: ValidationError[];
}

export interface ActorSchema {
    type: string;
    fields: FieldDefinition[];
}

// Cache types
export interface CachedSchema {
    schema: ActorSchema;
    timestamp: number;
}

export type ActorTypeCache = Map<string, CachedSchema>;

// ── WebSocket types ──

export type WebSocketEventName =
    | 'onCharacterGenerationStarted'
    | 'onCharacterGenerationCompleted'
    | 'onCharacterGenerationFailed'
    | 'onImageGenerationStarted'
    | 'onImageGenerationCompleted'
    | 'onImageGenerationFailed'
    | 'onItemGenerationStarted'
    | 'onItemGenerationCompleted'
    | 'onItemGenerationFailed'
    | 'onIngestionStarted'
    | 'onIngestionProgress'
    | 'onIngestionCompleted'
    | 'onIngestionCompendium'
    | 'onIngestionFailed'
    | 'onNotification'
    | 'onError'
    | 'onConnected'
    | 'onDisconnected'
    | 'onTranscriptionCompleted'
    | 'onTranscriptionFailed';

export type WebSocketEventHandler = (data: any) => void;
export type WebSocketEventHandlers = Record<WebSocketEventName, WebSocketEventHandler[]>;

/**
 * WebSocket message types — mirrors server enum
 * dev.agiro.masterserver.dto.WebSocketMessage.MessageType
 */
export type MessageType =
    // Character related events
    | 'CHARACTER_GENERATION_STARTED'
    | 'CHARACTER_GENERATION_COMPLETED'
    | 'CHARACTER_GENERATION_FAILED'
    // Image related events
    | 'IMAGE_GENERATION_STARTED'
    | 'IMAGE_GENERATION_COMPLETED'
    | 'IMAGE_GENERATION_FAILED'
    // Item related events
    | 'ITEM_GENERATION_REQUEST'
    | 'ITEM_GENERATION_STARTED'
    | 'ITEM_GENERATION_COMPLETED'
    | 'ITEM_GENERATION_FAILED'
    // Book ingestion events
    | 'INGESTION_STARTED'
    | 'INGESTION_PROGRESS'
    | 'INGESTION_COMPLETED'
    | 'INGESTION_COMPENDIUM'
    | 'INGESTION_FAILED'
    // Generic notifications
    | 'NOTIFICATION'
    | 'ERROR'
    // Ping / Pong
    | 'PING'
    | 'PONG'
    // Transcription events
    | 'TRANSCRIPTION_STARTED'
    | 'TRANSCRIPTION_COMPLETED'
    | 'TRANSCRIPTION_FAILED'
    // Custom game events
    | 'GAME_EVENT';

export interface WebSocketMessage {
    type: MessageType;
    sessionId: string;
    payload?: any;
    timestamp: number;
    error?: string;
}

// ── System profile / post-processing types ──

export interface SystemConstraint {
    type: string;
    fieldPath: string;
    description: string;
    parameters?: Record<string, any>;
}

export interface SystemProfile {
    systemId: string;
    systemSummary?: string;
    fieldGroups?: any[];
    detectedConstraints?: SystemConstraint[];
    valueRanges?: Record<string, any>;
    characterCreationSteps?: string[];

    /** Semantic mapping of system fields to universal RPG concepts */
    semanticMap?: SemanticMap;

    /** Detected roll mechanics for this system */
    rollMechanics?: RollMechanics;

    /** Overall confidence score (0-1) of the semantic mapping */
    confidence?: number;

    /** Example actor data used for few-shot generation */
    actorExamples?: Record<string, unknown>[];

    /** Example item data used for few-shot generation */
    itemExamples?: Record<string, unknown>[];
}

// ── Blueprint types ──

export interface BlueprintActor {
    type: string;
    fields: FieldDefinition[];
    coreFields: FieldDefinition[];
}

export interface BlueprintItemSchema {
    type: string;
    label: string;
    fields: FieldDefinition[];
    repeatable: boolean;
}

export interface FullBlueprint {
    systemId: string;
    systemVersion: string;
    actorType: string;
    timestamp: number;
    actor: BlueprintActor;
    items: Record<string, BlueprintItemSchema>;
    constraints: any[];
    metadata: Record<string, any>;
}

export interface AIBlueprint {
    systemId: string;
    actorType: string;
    actorFields: Partial<FieldDefinition>[];
    availableItems: Array<{
        type: string;
        label: string;
        fields: Partial<FieldDefinition>[];
        repeatable: boolean;
    }>;
    constraints: string[];
    coreFields: FieldDefinition[];
    example: any;
}

// ── Snapshot types ──

export interface SystemSnapshot {
    systemId: string;
    systemVersion: string;
    systemTitle: string;
    foundryVersion: string;
    worldId: string | null;
    timestamp: number;
    schemas: any;
    configData: any;
    compendiumSamples: any;
    worldExamples: any;
    valueDistributions: any;
    templateData: any;
    adapterHints: any;

    /** Roll mechanics detected from items and CONFIG */
    rollMechanics: RollMechanicsSnapshot | null;

    /** Derived/computed fields detected on actor documents */
    derivedFields: Record<string, DerivedFieldInfo[]>;
}

// ── Roll Mechanics types ──

export interface RollMechanicsSnapshot {
    /** Detected dice formula patterns (e.g. "1d20", "2d6", "Xd6") */
    diceFormulas: string[];
    /** Fields that appear to trigger or feed into rolls */
    rollTriggerFields: RollTriggerField[];
    /** Detected success model */
    successModel: 'target_number' | 'count_hits' | 'opposed' | 'pbta' | 'unknown';
    /** Whether skills are stored as embedded items (true in PF2e) or actor fields */
    skillAsItem: boolean;
}

export interface RollTriggerField {
    path: string;
    type: string;
    context: string; // e.g. "item action", "actor roll", "formula field"
}

export interface DerivedFieldInfo {
    path: string;
    isDerived: boolean;
    sourceHint?: string; // e.g. "computed from abilities"
}

// ── Semantic Map types (returned from server) ──

export type SemanticConcept =
    | 'health' | 'health_secondary' | 'level' | 'experience'
    | 'stat_strength' | 'stat_dexterity' | 'stat_constitution'
    | 'stat_intelligence' | 'stat_wisdom' | 'stat_charisma'
    | 'stat_generic'
    | 'skill_rank' | 'roll_attribute'
    | 'currency' | 'initiative' | 'armor_class'
    | 'damage_formula' | 'action_trigger'
    | 'movement_speed' | 'saving_throw'
    | 'unknown';

export interface FieldMapping {
    path: string;
    type: string;
    range?: [number, number];
    required: boolean;
    inferredAs: SemanticConcept;
    confidence: number;
}

export interface SemanticMap {
    health?: FieldMapping;
    healthSecondary?: FieldMapping;
    level?: FieldMapping;
    experience?: FieldMapping;
    primaryStats: FieldMapping[];
    skills: FieldMapping[];
    rollAttribute?: FieldMapping;
    currency: FieldMapping[];
    initiative?: FieldMapping;
    armorClass?: FieldMapping;
    movementSpeed?: FieldMapping;
}

export interface RollMechanics {
    formula: string;
    successModel: 'target_number' | 'count_hits' | 'opposed' | 'pbta' | 'unknown';
    modifierSource?: string;
    skillAsItem: boolean;
}

// ── Chat / Session types ──

export interface ChatEntry {
    sender: string;
    text: string;
    id?: string;
    timestamp?: Date | number;
}

// ── Speaker / Transcription types ──

/**
 * Metadata about the Foundry VTT user who was speaking when audio was recorded.
 * Sent alongside audio to the backend so every transcript has attribution.
 */
export interface SpeakerContext {
    /** Foundry user ID */
    userId: string;
    /** Foundry user display name */
    userName: string;
    /** True if the user is the GM */
    isGM: boolean;
    /** Actor ID assigned to this user in the current world */
    characterId: string | null;
    /** Actor name */
    characterName: string | null;
    /** Actor type (e.g. "character", "npc") */
    characterType: string | null;
    /** Foundry world ID */
    worldId: string | null;
    /** Active scene name at time of recording */
    sceneName: string | null;
    /** Game system ID (e.g. "dnd5e", "pf2e") */
    systemId: string | null;
    /** WebSocket session ID for result routing */
    sessionId: string;
    /** AV source that detected the speech ("livekit", "dom-observer") */
    avSource: string;
}

export interface BookInfo {
    id: string;
    title: string;
    [key: string]: any;
}

// ── Action types for session tab ──

export interface VTTAction {
    type: string;
    [key: string]: any;
}
