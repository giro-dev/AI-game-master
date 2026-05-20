/**
 * LibraryPanel — Book ingestion / Library submodule.
 *
 * Encapsulates PDF upload, compendium ingestion, and the book list.
 */

import type { BookInfo } from '../types/index.js';
import { getServerUrl } from '../settings.js';
import { showProgress, hideProgress, type PanelContext } from './panel-utils.js';

export class LibraryPanel {

    // ── State ──
    private books: BookInfo[] = [];

    constructor(private readonly ctx: PanelContext) {}

    /* ------------------------------------------------------------------ */
    /*  getData contribution                                                */
    /* ------------------------------------------------------------------ */

    async getData(): Promise<Partial<any>> {
        await this._refreshBooks();

        let knowledgePacks: any[] = [];
        try {
            knowledgePacks = game.packs
                ?.map((p: any) => ({
                    id: p.collection,
                    label: p.metadata?.label ?? `${p.metadata?.packageName}.${p.metadata?.name}`,
                    documentName: p.documentName ?? 'Document',
                    source: p.metadata?.packageName ?? 'world'
                }))
                ?.sort((a: any, b: any) => {
                    const labelCmp = String(a.label).localeCompare(String(b.label));
                    if (labelCmp !== 0) return labelCmp;
                    return String(a.documentName).localeCompare(String(b.documentName));
                }) ?? [];
        } catch (e) {
            console.warn('[AI-GM LibraryPanel] Pack enumeration failed:', e);
        }

        return {
            books: this.books.map((book: any) => ({
                ...book,
                displayTitle: book.bookTitle ?? book.title ?? 'Untitled knowledge source',
                sourceLabel: book.sourceType === 'compendium' ? 'Compendium' : 'PDF'
            })),
            knowledgePacks
        };
    }

    /* ------------------------------------------------------------------ */
    /*  activateListeners                                                   */
    /* ------------------------------------------------------------------ */

    activateListeners(html: any): void {
        html.find('[data-action="upload-book"]').on('click', this._onUploadBook.bind(this));
        html.find('[data-action="ingest-compendium"]').on('click', this._onIngestCompendium.bind(this));
        html.find('[data-action="delete-book"]').on('click', this._onDeleteBook.bind(this));
    }

    /* ------------------------------------------------------------------ */
    /*  Book management                                                     */
    /* ------------------------------------------------------------------ */

    private async _onUploadBook(_ev: any): Promise<void> {
        const html = this.ctx.element;
        const fileInput = html.find('#gm-book-file')[0] as HTMLInputElement | undefined;
        const file = fileInput?.files?.[0];
        if (!file) return ui.notifications.warn('Select a PDF first.');
        if (file.type !== 'application/pdf') return ui.notifications.warn('Only PDF files.');

        const bookTitle: string = html.find('#gm-book-title').val()?.trim() || file.name;
        const sessionId: string = game.aiGM?.wsClient?.getSessionId() ?? `upload-${Date.now()}`;

        showProgress(html, 'ingest', 'Uploading…', 0);
        this._subscribeIngestionProgress(html, sessionId);

        const body = new FormData();
        body.append('file', file);
        body.append('worldId', game.world.id);
        body.append('foundrySystem', game.system.id);
        body.append('bookTitle', bookTitle);
        body.append('sessionId', sessionId);

        try {
            const res = await fetch(`${getServerUrl()}/api/books/upload`, { method: 'POST', body });
            if (!res.ok) throw new Error(`Server ${res.status}`);
            ui.notifications.info(`Ingestion started for "${bookTitle}".`);
        } catch (e: any) {
            ui.notifications.error(`Upload failed: ${e.message}`);
            hideProgress(html, 'ingest');
        }
    }

    private async _onDeleteBook(ev: any): Promise<void> {
        const bookId: string | undefined = ev.currentTarget.dataset.bookId;
        if (!bookId) return;
        const yes = await Dialog.confirm({ title: 'Delete Book', content: '<p>Remove this book and all indexed data?</p>' });
        if (!yes) return;
        try {
            await fetch(`${getServerUrl()}/api/books/${bookId}`, { method: 'DELETE' });
            ui.notifications.info('Book removed.');
            this.ctx.render(false);
        } catch (e: any) {
            ui.notifications.error(`Delete failed: ${e.message}`);
        }
    }

    private async _onIngestCompendium(_ev: any): Promise<void> {
        const html = this.ctx.element;
        const packId: string = html.find('#gm-knowledge-pack').val();
        if (!packId) return ui.notifications.warn('Select a compendium first.');

        const pack = game.packs?.get(packId);
        if (!pack) return ui.notifications.error('Compendium not found.');

        const sessionId: string = game.aiGM?.wsClient?.getSessionId() ?? `compendium-${Date.now()}`;
        showProgress(html, 'ingest', 'Reading compendium…', 0);
        this._subscribeIngestionProgress(html, sessionId);

        try {
            const documents = await pack.getDocuments();
            const entries = documents
                .map((doc: any) => this._serializeCompendiumDocument(doc))
                .filter((entry: any) => entry?.text);

            if (!entries.length) {
                hideProgress(html, 'ingest');
                return ui.notifications.warn('This compendium has no usable entries to ingest.');
            }

            const res = await fetch(`${getServerUrl()}/api/books/compendium`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    worldId: game.world.id,
                    foundrySystem: game.system.id,
                    packId: pack.collection,
                    packLabel: pack.metadata?.label ?? pack.title ?? pack.collection,
                    documentType: pack.documentName ?? 'Document',
                    sessionId,
                    entries
                })
            });
            if (!res.ok) throw new Error(`Server ${res.status}`);
            ui.notifications.info(`Compendium ingestion started for "${pack.metadata?.label ?? pack.collection}".`);
        } catch (e: any) {
            ui.notifications.error(`Compendium ingestion failed: ${e.message}`);
            hideProgress(html, 'ingest');
        }
    }

    private _subscribeIngestionProgress(html: any, _sessionId: string): void {
        const ws = game.aiGM?.wsClient;
        if (!ws) return;

        const handler = (event: any): void => {
            if (!event) return;
            showProgress(html, 'ingest', event.message ?? 'Processing…', event.progress ?? 0);
            if (event.status === 'COMPLETED' || event.status === 'FAILED') {
                setTimeout(() => {
                    hideProgress(html, 'ingest');
                    this.ctx.render(false);
                }, 1500);
            }
        };
        ws.on('onIngestionStarted', handler);
        ws.on('onIngestionProgress', handler);
        ws.on('onIngestionCompleted', handler);
        ws.on('onIngestionFailed', (event: any) => {
            showProgress(html, 'ingest', `Failed: ${event?.error ?? 'unknown'}`, 100);
            setTimeout(() => { hideProgress(html, 'ingest'); this.ctx.render(false); }, 3000);
        });
    }

    private async _refreshBooks(): Promise<void> {
        const worldId = game.world?.id;
        if (!worldId) { this.books = []; return; }
        try {
            const res = await fetch(`${getServerUrl()}/api/books/${worldId}`);
            this.books = res.ok ? await res.json() : [];
        } catch { this.books = []; }
    }

    private _serializeCompendiumDocument(doc: any): any | null {
        if (!doc) return null;

        const raw = typeof doc.toObject === 'function' ? doc.toObject() : doc;
        const type = raw.type ?? doc.documentName ?? doc.type ?? 'Document';
        const name = raw.name ?? doc.name ?? 'Unnamed';
        const documentName = doc.documentName ?? doc.constructor?.name ?? 'Document';
        const sections: string[] = [
            `Compendium entry: ${name}`,
            `Document class: ${documentName}`,
            `Type: ${type}`
        ];

        if (raw.system && Object.keys(raw.system).length) {
            sections.push(`System data:\n${JSON.stringify(raw.system, null, 2)}`);
        }

        if (Array.isArray(raw.items) && raw.items.length) {
            const itemSummary = raw.items.map((item: any) => ({
                name: item.name, type: item.type, system: item.system ?? {}
            }));
            sections.push(`Embedded items:\n${JSON.stringify(itemSummary, null, 2)}`);
        }

        if (Array.isArray(raw.pages) && raw.pages.length) {
            const pageContent = raw.pages
                .map((page: any) => {
                    const text = page.text?.content ?? page.src ?? page.name ?? '';
                    return text ? `Page: ${page.name ?? 'Unnamed'}\n${text}` : null;
                })
                .filter(Boolean)
                .join('\n\n');
            if (pageContent) sections.push(pageContent);
        }

        if (Array.isArray(raw.results) && raw.results.length) {
            const results = raw.results.map((r: any) => r.text ?? r.documentCollection ?? r.documentId).filter(Boolean);
            if (results.length) sections.push(`Table results:\n- ${results.join('\n- ')}`);
        }

        const description = raw.system?.description?.value
            ?? raw.system?.description
            ?? raw.description
            ?? raw.content
            ?? raw.biography
            ?? '';
        if (description) {
            const stripped = String(description).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
            if (stripped) sections.push(`Description:\n${stripped}`);
        }

        if (sections.length <= 3) {
            sections.push(`Raw data:\n${JSON.stringify(raw, null, 2)}`);
        }

        return {
            id: raw._id ?? doc.id ?? name,
            name, type,
            text: sections.join('\n\n'),
            metadata: { document_name: documentName }
        };
    }
}
