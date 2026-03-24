/**
 * Ambient declarations for Foundry VTT globals and CDN dependencies.
 * These cover APIs not fully typed by @league-of-foundry-developers/foundry-vtt-types.
 */

/* ── CDN globals (loaded via module.json scripts) ── */
declare const SockJS: new (url: string) => any;
declare const Stomp: { over(socket: any): any };

/* ── Foundry VTT ambient types (extend as needed) ── */
declare const game: any;
declare const ui: any;
declare const canvas: any;
declare const Hooks: any;
declare const CONFIG: any;
declare const Actor: any;
declare const Item: any;
declare const Dialog: any;
declare const ChatMessage: any;

declare namespace foundry {
    namespace utils {
        function mergeObject(original: any, other: any, options?: any): any;
        function deepClone<T>(original: T): T;
    }
}

declare class Application {
    constructor(options?: any);
    static get defaultOptions(): any;
    get element(): any;
    getData(_options?: any): Promise<any> | any;
    activateListeners(html: any): void;
    render(force?: boolean, options?: any): this;
    options: any;
}

