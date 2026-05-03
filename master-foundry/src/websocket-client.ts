/**
 * WebSocket client for bidirectional communication with the Game Master server
 * Handles real-time events like character generation, image generation, and notifications
 */

import type { WebSocketEventName, WebSocketEventHandler, WebSocketEventHandlers, WebSocketMessage } from './types/index.js';

export class WebSocketClient {
    private readonly serverUrl: string;
    private stompClient: any;
    private connected: boolean;
    private sessionId: string;
    private subscriptions: Map<string, any>;
    private reconnectAttempts: number;
    private readonly maxReconnectAttempts: number;
    private readonly reconnectDelay: number;
    private eventHandlers: WebSocketEventHandlers;

    constructor(serverUrl: string = 'http://localhost:8080') {
        this.serverUrl = serverUrl;
        this.stompClient = null;
        this.connected = false;
        this.sessionId = this._generateSessionId();
        this.subscriptions = new Map();
        this.reconnectAttempts = 0;
        this.maxReconnectAttempts = 5;
        this.reconnectDelay = 2000;

        this.eventHandlers = {
            onCharacterGenerationStarted: [],
            onCharacterGenerationCompleted: [],
            onCharacterGenerationFailed: [],
            onImageGenerationStarted: [],
            onImageGenerationCompleted: [],
            onImageGenerationFailed: [],
            onItemGenerationStarted: [],
            onItemGenerationCompleted: [],
            onItemGenerationFailed: [],
            onIngestionStarted: [],
            onIngestionProgress: [],
            onIngestionCompleted: [],
            onIngestionCompendium: [],
            onIngestionFailed: [],
            onNotification: [],
            onError: [],
            onConnected: [],
            onDisconnected: []
        };
    }

    /**
     * Connect to WebSocket server
     */
    async connect(): Promise<void> {
        if (this.connected) {
            console.log('[WebSocket] Already connected');
            return;
        }

        console.log('[WebSocket] Connecting to server...');

        return new Promise<void>((resolve, reject) => {
            const socket = new SockJS(`${this.serverUrl}/ws`);
            this.stompClient = Stomp.over(socket);

            // Disable debug logging in production
            this.stompClient.debug = (_str: string) => {
                // console.debug('[STOMP]', str);
            };

            this.stompClient.connect(
                {},
                (_frame: any) => {
                    this.connected = true;
                    this.reconnectAttempts = 0;
                    console.log('[WebSocket] Connected:', _frame);

                    this._subscribeToTopics();
                    this._triggerEvent('onConnected', { sessionId: this.sessionId });

                    resolve();
                },
                (error: any) => {
                    this.connected = false;
                    console.error('[WebSocket] Connection error:', error);

                    this._triggerEvent('onDisconnected', { error });
                    this._attemptReconnect();

                    reject(error);
                }
            );
        });
    }

    /**
     * Disconnect from WebSocket server
     */
    disconnect(): void {
        if (this.stompClient && this.connected) {
            this.stompClient.disconnect(() => {
                console.log('[WebSocket] Disconnected');
                this.connected = false;
                this._triggerEvent('onDisconnected', {});
            });
        }
    }

    /**
     * Subscribe to relevant topics
     */
    private _subscribeToTopics(): void {
        this._subscribe(`/queue/character-${this.sessionId}`, (message: any) => {
            this._handleCharacterMessage(JSON.parse(message.body));
        });

        this._subscribe(`/queue/image-${this.sessionId}`, (message: any) => {
            this._handleImageMessage(JSON.parse(message.body));
        });

        this._subscribe(`/queue/item-${this.sessionId}`, (message: any) => {
            this._handleItemMessage(JSON.parse(message.body));
        });

        this._subscribe(`/queue/ingestion-${this.sessionId}`, (message: any) => {
            this._handleIngestionMessage(JSON.parse(message.body));
        });

        this._subscribe(`/queue/${this.sessionId}`, (message: any) => {
            this._handleMessage(JSON.parse(message.body));
        });

        this._subscribe('/topic/game-events', (message: any) => {
            this._handleMessage(JSON.parse(message.body));
        });

        console.log('[WebSocket] Subscribed to topics for session:', this.sessionId);
    }

    /**
     * Subscribe to a topic
     */
    private _subscribe(destination: string, callback: (message: any) => void): void {
        if (!this.stompClient || !this.connected) {
            console.warn('[WebSocket] Not connected, cannot subscribe to:', destination);
            return;
        }

        const subscription = this.stompClient.subscribe(destination, callback);
        this.subscriptions.set(destination, subscription);
    }

    /**
     * Send a message to the server
     */
    send(destination: string, message: any): boolean {
        if (!this.stompClient || !this.connected) {
            console.error('[WebSocket] Not connected, cannot send message');
            return false;
        }

        const wsMessage: WebSocketMessage = {
            type: message.type || 'GAME_EVENT',
            sessionId: this.sessionId,
            payload: message.payload || message,
            timestamp: Date.now()
        };

        this.stompClient.send(destination, {}, JSON.stringify(wsMessage));
        return true;
    }

    /**
     * Send a ping message
     */
    ping(): boolean {
        return this.send('/app/ping', { type: 'PING' });
    }

    /**
     * Handle character generation messages
     */
    private _handleCharacterMessage(message: any): void {
        console.log('[WebSocket] Character message:', message);

        switch (message.type) {
            case 'CHARACTER_GENERATION_STARTED':
                this._triggerEvent('onCharacterGenerationStarted', message.payload);
                ui.notifications.info('Character generation started...');
                break;

            case 'CHARACTER_GENERATION_COMPLETED':
                this._triggerEvent('onCharacterGenerationCompleted', message.payload);
                ui.notifications.info('Character generation completed!');
                break;

            case 'CHARACTER_GENERATION_FAILED':
                this._triggerEvent('onCharacterGenerationFailed', message);
                ui.notifications.error(`Character generation failed: ${message.error}`);
                break;
        }
    }

    /**
     * Handle image generation messages
     */
    private _handleImageMessage(message: any): void {
        console.log('[WebSocket] Image message:', message);

        switch (message.type) {
            case 'IMAGE_GENERATION_STARTED':
                this._triggerEvent('onImageGenerationStarted', message.payload);
                ui.notifications.info('Image generation started...');
                break;

            case 'IMAGE_GENERATION_COMPLETED':
                this._triggerEvent('onImageGenerationCompleted', message.payload);
                ui.notifications.info('Image generated successfully!');
                break;

            case 'IMAGE_GENERATION_FAILED':
                this._triggerEvent('onImageGenerationFailed', message);
                ui.notifications.error(`Image generation failed: ${message.error}`);
                break;
        }
    }

    /**
     * Handle item generation messages
     */
    private _handleItemMessage(message: any): void {
        console.log('[WebSocket] Item message:', message);

        switch (message.type) {
            case 'ITEM_GENERATION_STARTED':
                this._triggerEvent('onItemGenerationStarted', message.payload);
                ui.notifications.info('Item generation started...');
                break;

            case 'ITEM_GENERATION_COMPLETED':
                this._triggerEvent('onItemGenerationCompleted', message.payload);
                ui.notifications.info('Item generated successfully!');
                break;

            case 'ITEM_GENERATION_FAILED':
                this._triggerEvent('onItemGenerationFailed', message);
                ui.notifications.error(`Item generation failed: ${message.error}`);
                break;
        }
    }

    /**
     * Handle book ingestion messages
     */
    private _handleIngestionMessage(message: any): void {
        console.log('[WebSocket] Ingestion message:', message);

        switch (message.type) {
            case 'INGESTION_STARTED':
                this._triggerEvent('onIngestionStarted', message.payload);
                break;

            case 'INGESTION_PROGRESS':
                this._triggerEvent('onIngestionProgress', message.payload);
                break;

            case 'INGESTION_COMPLETED':
                this._triggerEvent('onIngestionCompleted', message.payload);
                break;

            case 'INGESTION_COMPENDIUM':
                this._triggerEvent('onIngestionCompendium', message.payload);
                break;

            case 'INGESTION_FAILED':
                this._triggerEvent('onIngestionFailed', message.payload);
                break;
        }
    }

    /**
     * Handle generic messages
     */
    private _handleMessage(message: any): void {
        console.log('[WebSocket] Message:', message);

        switch (message.type) {
            case 'NOTIFICATION':
                this._triggerEvent('onNotification', message.payload);
                if (message.payload?.text) {
                    ui.notifications.info(message.payload.text);
                }
                break;

            case 'ERROR':
                this._triggerEvent('onError', message);
                if (message.error) {
                    ui.notifications.error(message.error);
                }
                break;

            case 'PONG':
                console.log('[WebSocket] Pong received');
                break;
        }
    }

    /**
     * Attempt to reconnect
     */
    private _attemptReconnect(): void {
        if (this.reconnectAttempts >= this.maxReconnectAttempts) {
            console.error('[WebSocket] Max reconnection attempts reached');
            ui.notifications.error('Failed to connect to Game Master server');
            return;
        }

        this.reconnectAttempts++;
        const delay = this.reconnectDelay * this.reconnectAttempts;

        console.log(`[WebSocket] Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts}/${this.maxReconnectAttempts})`);

        setTimeout(() => {
            this.connect().catch(err => {
                console.error('[WebSocket] Reconnection failed:', err);
            });
        }, delay);
    }

    /**
     * Register an event handler
     */
    on(event: WebSocketEventName, handler: WebSocketEventHandler): void {
        if (this.eventHandlers[event]) {
            this.eventHandlers[event].push(handler);
        } else {
            console.warn(`[WebSocket] Unknown event type: ${event}`);
        }
    }

    /**
     * Unregister an event handler
     */
    off(event: WebSocketEventName, handler: WebSocketEventHandler | null): void {
        if (this.eventHandlers[event]) {
            const index = this.eventHandlers[event].indexOf(handler as WebSocketEventHandler);
            if (index > -1) {
                this.eventHandlers[event].splice(index, 1);
            }
        }
    }

    /**
     * Trigger event handlers
     */
    private _triggerEvent(event: WebSocketEventName, data: any): void {
        if (this.eventHandlers[event]) {
            this.eventHandlers[event].forEach(handler => {
                try {
                    handler(data);
                } catch (error) {
                    console.error(`[WebSocket] Error in ${event} handler:`, error);
                }
            });
        }
    }

    /**
     * Generate a unique session ID
     */
    private _generateSessionId(): string {
        return `foundry-${game.user.id}-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
    }

    /**
     * Get the current session ID
     */
    getSessionId(): string {
        return this.sessionId;
    }

    /**
     * Check if connected
     */
    isConnected(): boolean {
        return this.connected;
    }

    /**
     * Send item generation request
     */
    generateItems(prompt: string, options: {
        packId?: string;
        actorType?: string;
        blueprint?: any;
        requestId?: string;
        validItemTypes?: string[];
    } = {}): boolean {
        const destination = '/app/item/generate';
        const message = {
            type: 'ITEM_GENERATION_REQUEST',
            payload: {
                prompt,
                packId: options.packId,
                systemId: game.system.id,
                worldId: game.world.id,
                actorType: options.actorType || 'item',
                blueprint: options.blueprint,
                requestId: options.requestId || this.sessionId,
                sessionId: this.sessionId,
                validItemTypes: options.validItemTypes
            }
        };
        return this.send(destination, message);
    }
}

