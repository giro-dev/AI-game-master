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
    | 'onIngestionFailed'
    | 'onNotification'
    | 'onError'
    | 'onConnected'
    | 'onDisconnected';

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
    | 'INGESTION_FAILED'
    // Generic notifications
    | 'NOTIFICATION'
    | 'ERROR'
    // Ping / Pong
    | 'PING'
    | 'PONG'
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
}

// ── Chat / Session types ──

export interface ChatEntry {
    sender: string;
    text: string;
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
