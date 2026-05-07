/**
 * AI Game Master — Shared panel utilities and PanelContext interface.
 *
 * Utilities that are used by more than one subpanel live here so that
 * there is a single, testable implementation.
 */

/**
 * Minimal interface that every subpanel receives so it can trigger
 * re-renders and access the host Application's DOM element without
 * depending on the concrete AIGameMasterPanel class.
 */
export interface PanelContext {
    /** The jQuery/HTMLElement of the rendered Application window. */
    readonly element: any;
    /** Request a re-render of the host Application. */
    render(force?: boolean): void;
    /** Returns the last adventure roll captured by the FeaturesPanel. */
    getLastRoll(): any;
    /** Returns the actor type currently selected in the GeneratePanel. */
    getSelectedActorType(): string;
    /** Returns the active adventure session ID (null when no session is running). */
    getAdventureSessionId(): string | null;
}

/* ── Progress bar helpers ───────────────────────────────────────────── */

export function showProgress(html: any, prefix: string, message: string, percent: number): void {
    const container = html.find(`#${prefix}-progress`);
    container.addClass('active');
    container.find(`#${prefix}-progress-text`).text(message);
    container.find(`#${prefix}-progress-bar`).css('width', `${percent}%`);
}

export function hideProgress(html: any, prefix: string): void {
    html.find(`#${prefix}-progress`).removeClass('active');
}

/* ── String helpers ─────────────────────────────────────────────────── */

export function escapeHtml(text: string): string {
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

export function stripHtml(html: string): string {
    if (!html) return '';
    const tmp = document.createElement('DIV');
    tmp.innerHTML = html;
    return tmp.textContent || tmp.innerText || '';
}
