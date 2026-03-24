/**
 * WebSocket Session Manager
 * Manages WebSocket event handlers lifecycle for character generation
 *
 * @module WebSocketSessionManager
 */

interface Session {
    active: boolean;
    progressHandler: ((data: any) => void) | null;
    completedHandler: ((data: any) => void) | null;
    failedHandler: ((error: any) => void) | null;
    cleanup: () => void;
}

export class WebSocketSessionManager {
    private currentSession: Session | null = null;

    /**
     * Create a new generation session
     */
    createSession(button: JQuery<HTMLElement>): Session {
        // Cleanup previous session if exists
        this.cleanup();

        const session: Session = {
            active: true,
            progressHandler: null,
            completedHandler: null,
            failedHandler: null,
            cleanup: () => this.cleanupSession(session)
        };

        // Progress handler
        session.progressHandler = (data: any): void => {
            if (!session.active) return;
            console.log('[WS Session] Progress:', data);
            if (data.currentStep) {
                button.html(`<i class="fas fa-spinner fa-spin"></i> ${data.currentStep}`);
            }
        };

        // Completion handler
        session.completedHandler = (data: any): void => {
            if (!session.active) return;
            console.log('[WS Session] Completed:', data);
            this.cleanupSession(session);
        };

        // Failure handler
        session.failedHandler = (error: any): void => {
            if (!session.active) return;
            console.error('[WS Session] Failed:', error);
            ui.notifications?.error(`Generation failed: ${error.error || 'Unknown error'}`);
            this.cleanupSession(session);
        };

        // Register handlers
        this.registerHandlers(session);

        // Store current session
        this.currentSession = session;

        return session;
    }

    /**
     * Register WebSocket event handlers
     */
    private registerHandlers(session: Session): void {
        const wsClient = (game as any).aiGM?.wsClient;

        if (!wsClient) {
            console.warn('[WS Session] WebSocket client not available');
            return;
        }

        wsClient.on('onCharacterGenerationStarted', session.progressHandler);
        wsClient.on('onCharacterGenerationCompleted', session.completedHandler);
        wsClient.on('onCharacterGenerationFailed', session.failedHandler);

        console.log('[WS Session] Handlers registered');
    }

    /**
     * Unregister WebSocket event handlers
     */
    private unregisterHandlers(session: Session): void {
        const wsClient = (game as any).aiGM?.wsClient;
        if (!wsClient) return;

        wsClient.off('onCharacterGenerationStarted', session.progressHandler);
        wsClient.off('onCharacterGenerationCompleted', session.completedHandler);
        wsClient.off('onCharacterGenerationFailed', session.failedHandler);

        console.log('[WS Session] Handlers unregistered');
    }

    /**
     * Cleanup a session
     */
    private cleanupSession(session: Session): void {
        if (!session.active) return;

        session.active = false;
        this.unregisterHandlers(session);
        console.log('[WS Session] Session cleaned up');
    }

    /**
     * Cleanup current session
     */
    cleanup(): void {
        if (this.currentSession) {
            this.cleanupSession(this.currentSession);
            this.currentSession = null;
        }
    }

    /**
     * Check if WebSocket is available and connected
     */
    isAvailable(): boolean {
        const wsClient = (game as any).aiGM?.wsClient;
        return wsClient?.isConnected() ?? false;
    }

    /**
     * Get WebSocket session ID
     */
    getSessionId(): string | null {
        const wsClient = (game as any).aiGM?.wsClient;
        return wsClient?.getSessionId() ?? null;
    }
}

