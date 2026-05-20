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
    worldId?: string;
    referenceCharacter?: {
        systemId: string;
        actorType: string;
        label: string;
        actorData: Record<string, any>;
        items: Array<Record<string, any>>;
        capturedAt?: number;
    };
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
    | 'onTranscriptionReceived'
    | 'onIntentConfirmationRequest'
    | 'onIntentConfirmed'
    | 'onIntentRejected'
    | 'onDirectorNarration'
    | 'onDirectorAudioReady'
    | 'onNpcDialogueAudio'
    | 'onNpcAudioReady'
    | 'onSceneTransition'
    | 'onAdventureStateUpdate'
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
    // Transcription events
    | 'TRANSCRIPTION_COMPLETED'
    // Adventure Director events
    | 'TRANSCRIPTION_RECEIVED'
    | 'INTENT_CONFIRMATION_REQUEST'
    | 'INTENT_CONFIRMED'
    | 'INTENT_REJECTED'
    | 'DIRECTOR_NARRATION'
    | 'DIRECTOR_AUDIO_READY'
    | 'NPC_DIALOGUE_AUDIO'
    | 'NPC_AUDIO_READY'
    | 'SCENE_TRANSITION'
    | 'ADVENTURE_STATE_UPDATE'
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
    bookId?: string;
    id?: string;
    bookTitle?: string;
    title?: string;
    sourceType?: string;
    sourceId?: string;
    [key: string]: any;
}

// ── Action types for session tab ──

export interface VTTAction {
    type: string;
    [key: string]: any;
}

// ── Adventure types ──

export interface AdventureNpcInfo {
    id: string;
    name: string;
    personality?: string;
    secrets?: string;
    objectives?: string;
    currentDisposition?: string;
    voiceId?: string;
}

export interface AdventureSummary {
    id: string;
    title: string;
    system: string;
    synopsis?: string;
    actCount?: number;
    npcCount?: number;
    clueCount?: number;
    npcs?: AdventureNpcInfo[];
}

export interface AdventureSceneInfo {
    title: string;
    readAloudText: string;
}

export interface AdventureRollInfo {
    messageId?: string;
    actorId?: string;
    actorName?: string;
    speakerAlias?: string;
    tokenId?: string;
    flavor?: string;
    formula?: string;
    total?: number;
    target?: number | null;
    margin?: number | null;
    outcome?: string | null;
    success?: boolean | null;
    content?: string;
    dice?: Array<{
        faces?: number;
        results: number[];
    }>;
    rolledAt?: number;
}

export interface AdventureStartResponse {
    sessionId: string;
    adventureId: string;
    sessionName?: string;
    participantNames?: string[];
    sessionSummary?: string;
    currentActId?: string;
    currentSceneId?: string;
    currentScene?: AdventureSceneInfo;
    discoveredClues?: string[];
    metNpcs?: string[];
    tensionLevel?: number;
}

export interface AdventureSavedSessionSummary {
    id: string;
    sessionName: string;
    participantNames?: string[];
    sessionSummary?: string;
    currentSceneId?: string;
    currentSceneTitle?: string;
    tensionLevel: number;
    createdAt?: string;
    updatedAt?: string;
}

export interface NpcDialoguePayload {
    npcId: string;
    npcName?: string;
    text: string;
    voiceId?: string;
    emotion?: string;
    /** URL to fetch the audio (preferred, avoids large base64 in WebSocket). */
    audioUrl?: string;
    /** @deprecated Use audioUrl instead. */
    audioBase64?: string;
}

export interface IntentConfirmationPayload {
    question: string;
    reasoning?: string;
}

export interface DirectorNarrationPayload {
    narration: string;
    /** URL to fetch the narration audio (preferred, avoids large base64 in WebSocket). */
    narrationAudioUrl?: string;
    /** @deprecated Use narrationAudioUrl instead. */
    narrationAudioBase64?: string;
    actions?: VTTAction[];
    reasoning?: string;
}

export interface AdventureStateUpdatePayload {
    discoveredClues?: string[];
    npcDispositionChanges?: Record<string, string>;
    transitionTriggered?: string;
    tensionDelta?: number;
}

// ── Character Template types ──

export interface CharacterTemplate {
    id: string;
    name: string;
    description: string;
    actorType: string;
    promptText: string;
    selectedFields: string[];
    language: string;
    itemTypes: string[];
    tags: string[];
    createdAt: number;
    updatedAt: number;
}

// ── Creation Wizard types ──

export type CreationWizardStep = 'template' | 'fields' | 'prompt' | 'preview';

export interface CreationWizardState {
    currentStep: CreationWizardStep;
    selectedTemplate: CharacterTemplate | null;
    actorType: string;
    selectedFields: Set<string>;
    prompt: string;
    language: string;
    generatedData: CharacterData | null;
    validationErrors: ValidationError[];
    batchCount: number;
    batchResults: CharacterData[];
}

// ── Preview/Edit types ──

export interface EditableField {
    path: string;
    label: string;
    type: string;
    value: any;
    originalValue: any;
    min?: number;
    max?: number;
    choices?: any[];
    modified: boolean;
}

export interface CharacterPreview {
    actor: {
        name: string;
        type: string;
        img: string;
        editableFields: EditableField[];
    };
    items: Array<{
        name: string;
        type: string;
        editableFields: EditableField[];
    }>;
    validationErrors: ValidationError[];
}

export interface AdventureSession {
    id: string;
    adventureModuleId: string;
    worldId?: string;
    sessionName?: string;
    sessionSummary?: string;
    participantNames?: string[];
    currentActId?: string;
    currentSceneId?: string;
    discoveredClueIds?: string[];
    metNpcIds?: string[];
    npcDispositions?: Record<string, string>;
    playerDecisionLog?: string[];
    tensionLevel: number;
}
