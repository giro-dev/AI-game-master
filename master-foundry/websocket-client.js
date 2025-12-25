/**
 * WebSocket client for bidirectional communication with the Game Master server
 * Handles real-time events like character generation, image generation, and notifications
 */

export class WebSocketClient {
    constructor(serverUrl = 'http://localhost:8080') {
        this.serverUrl = serverUrl;
        this.stompClient = null;
        this.connected = false;
        this.sessionId = this._generateSessionId();
        this.subscriptions = new Map();
        this.reconnectAttempts = 0;
        this.maxReconnectAttempts = 5;
        this.reconnectDelay = 2000;

        // Event handlers
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
            onNotification: [],
            onError: [],
            onConnected: [],
            onDisconnected: []
        };
    }

    /**
     * Connect to WebSocket server
     */
    async connect() {
        if (this.connected) {
            console.log('[WebSocket] Already connected');
            return;
        }

        console.log('[WebSocket] Connecting to server...');

        return new Promise((resolve, reject) => {
            // Use SockJS for compatibility
            const socket = new SockJS(`${this.serverUrl}/ws`);
            this.stompClient = Stomp.over(socket);

            // Disable debug logging in production
            this.stompClient.debug = (str) => {
                // console.debug('[STOMP]', str);
            };

            this.stompClient.connect(
                {},
                (frame) => {
                    this.connected = true;
                    this.reconnectAttempts = 0;
                    console.log('[WebSocket] Connected:', frame);

                    this._subscribeToTopics();
                    this._triggerEvent('onConnected', { sessionId: this.sessionId });

                    resolve();
                },
                (error) => {
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
    disconnect() {
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
    _subscribeToTopics() {
        // Subscribe to character generation updates for this session
        this._subscribe(`/queue/character-${this.sessionId}`, (message) => {
            this._handleCharacterMessage(JSON.parse(message.body));
        });

        // Subscribe to image generation updates for this session
        this._subscribe(`/queue/image-${this.sessionId}`, (message) => {
            this._handleImageMessage(JSON.parse(message.body));
        });

        // Subscribe to item generation updates for this session
        this._subscribe(`/queue/item-${this.sessionId}`, (message) => {
            this._handleItemMessage(JSON.parse(message.body));
        });

        // Subscribe to personal queue
        this._subscribe(`/queue/${this.sessionId}`, (message) => {
            this._handleMessage(JSON.parse(message.body));
        });

        // Subscribe to broadcast game events
        this._subscribe('/topic/game-events', (message) => {
            this._handleMessage(JSON.parse(message.body));
        });

        console.log('[WebSocket] Subscribed to topics for session:', this.sessionId);
    }

    /**
     * Subscribe to a topic
     */
    _subscribe(destination, callback) {
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
    send(destination, message) {
        if (!this.stompClient || !this.connected) {
            console.error('[WebSocket] Not connected, cannot send message');
            return false;
        }

        const wsMessage = {
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
    ping() {
        return this.send('/app/ping', { type: 'PING' });
    }

    /**
     * Handle character generation messages
     */
    _handleCharacterMessage(message) {
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
    _handleImageMessage(message) {
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
    _handleItemMessage(message) {
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
     * Handle generic messages
     */
    _handleMessage(message) {
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
    _attemptReconnect() {
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
    on(event, handler) {
        if (this.eventHandlers[event]) {
            this.eventHandlers[event].push(handler);
        } else {
            console.warn(`[WebSocket] Unknown event type: ${event}`);
        }
    }

    /**
     * Unregister an event handler
     */
    off(event, handler) {
        if (this.eventHandlers[event]) {
            const index = this.eventHandlers[event].indexOf(handler);
            if (index > -1) {
                this.eventHandlers[event].splice(index, 1);
            }
        }
    }

    /**
     * Trigger event handlers
     */
    _triggerEvent(event, data) {
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
    _generateSessionId() {
        return `foundry-${game.user.id}-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
    }

    /**
     * Get the current session ID
     */
    getSessionId() {
        return this.sessionId;
    }

    /**
     * Check if connected
     */
    isConnected() {
        return this.connected;
    }

    /**
     * Send item generation request
     */
    generateItems(prompt, options = {}) {
        const destination = '/app/item/generate';
        const message = {
            type: 'ITEM_GENERATION_REQUEST',
            payload: {
                prompt,
                packId: options.packId,
                systemId: game.system.id,
                actorType: options.actorType || 'item',
                blueprint: options.blueprint,
                requestId: options.requestId || this.sessionId,
                sessionId: this.sessionId
            }
        };
        return this.send(destination, message);
    }
}
