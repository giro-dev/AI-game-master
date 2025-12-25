import { localize } from './utils/localize.js';

export class ItemGeneratorUI extends FormApplication {
    constructor(options = {}) {
        super(options);
        this.packId = game.settings.get('ai-gm', 'defaultItemPack') || null;
        this.lastPrompt = '';
    }

    static get defaultOptions() {
        return foundry.utils.mergeObject(super.defaultOptions, {
            id: 'ai-gm-item-generator',
            title: 'AI Item Generator',
            template: 'modules/master-foundry/templates/item-generator.html',
            width: 500,
            height: 'auto'
        });
    }

    getData() {
        return {
            packId: this.packId,
            packs: game.packs.filter(p => p.documentName === 'Item').map(p => ({ id: p.collection, label: `${p.metadata.package}.${p.metadata.name}` })),
            prompt: this.lastPrompt
        };
    }

    async _updateObject(event, formData) {
        const prompt = formData.prompt?.trim();
        const packId = formData.packId;
        if (!prompt) {
            ui.notifications.warn('Prompt is required');
            return;
        }
        if (!packId) {
            ui.notifications.warn('Select a compendium pack');
            return;
        }

        this.packId = packId;
        this.lastPrompt = prompt;
        await game.settings.set('ai-gm', 'defaultItemPack', packId);

        const ws = game.aiGM?.wsClient;
        if (!ws || !ws.isConnected()) {
            ui.notifications.error('WebSocket not connected');
            return;
        }

        const requestId = `${ws.getSessionId()}-${Date.now()}`;
        const ok = ws.generateItems(prompt, { packId, requestId });
        if (ok) {
            ui.notifications.info('Item generation sent');
        }
    }
}

