/**
 * Character Generator UI
 * Provides GM interface for AI-driven character generation
 */

export class CharacterGeneratorUI extends FormApplication {
    constructor(options = {}) {
        super({}, options);
        this.characterData = null;
        this.blueprint = null;
        this.validationErrors = [];
    }

    static get defaultOptions() {
        return foundry.utils.mergeObject(super.defaultOptions, {
            id: 'ai-character-generator',
            title: 'AI Character Generator',
            template: 'modules/ai-gm/templates/character-generator.html',
            classes: ['ai-gm', 'character-generator'],
            width: 600,
            height: 'auto',
            closeOnSubmit: false,
            submitOnChange: false,
            tabs: [{navSelector: '.tabs', contentSelector: '.content', initial: 'generate'}]
        });
    }

    getData() {
        const context = super.getData();

        context.systemId = game.system.id;
        context.systemName = game.system.title;

        // Get available actor types from the blueprint generator
        if (game.aiGM?.blueprintGenerator) {
            context.actorTypes = game.aiGM.blueprintGenerator.schemaExtractor.getActorTypes();
            context.actorTypeLabels = game.aiGM.blueprintGenerator.schemaExtractor.getActorTypeLabels();
        } else {
            context.actorTypes = ['character'];
            context.actorTypeLabels = { character: 'Character' };
        }

        context.hasAdapter = game.aiGM?.adapterRegistry?.hasAdapterForCurrentSystem() || false;
        context.adapterName = game.aiGM?.adapterRegistry?.getActive()?.getName() || 'None';

        context.characterData = this.characterData;
        context.validationErrors = this.validationErrors;

        return context;
    }

    activateListeners(html) {
        super.activateListeners(html);

        html.find('#generate-character').click(this._onGenerateCharacter.bind(this));
        html.find('#create-character').click(this._onCreateCharacter.bind(this));
        html.find('#view-blueprint').click(this._onViewBlueprint.bind(this));
        html.find('#export-json').click(this._onExportJSON.bind(this));
    }

    async _onGenerateCharacter(event) {
        event.preventDefault();

        const form = this.element.find('form')[0];
        const formData = new FormData(form);

        const actorType = formData.get('actorType') || 'character';
        const prompt = formData.get('prompt');
        const language = formData.get('language') || 'en';

        if (!prompt || prompt.trim() === '') {
            ui.notifications.warn('Please enter a character description');
            return;
        }

        // Show loading state
        const button = this.element.find('#generate-character');
        button.prop('disabled', true).text('Generating...');

        try {
            // Generate blueprint
            const blueprint = game.aiGM.blueprintGenerator.generateAIBlueprint(actorType);
            this.blueprint = blueprint;

            // Call backend API
            const response = await fetch('http://localhost:8080/gm/character/generate', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    prompt: prompt,
                    actorType: actorType,
                    blueprint: blueprint,
                    language: language
                })
            });

            if (!response.ok) {
                throw new Error(`Server responded with ${response.status}`);
            }

            const data = await response.json();
            this.characterData = data.character;

            // Validate
            const adapter = game.aiGM.adapterRegistry.getActive();
            if (adapter) {
                this.characterData = adapter.preprocessAIData(this.characterData);
            }

            const validation = game.aiGM.blueprintGenerator.validateCharacter(
                this.characterData,
                game.aiGM.blueprintGenerator.generateBlueprint(actorType)
            );

            this.validationErrors = validation.errors;

            if (validation.valid) {
                ui.notifications.info('Character generated successfully!');
            } else {
                ui.notifications.warn(`Character generated with ${validation.errors.length} validation warnings`);
            }

            this.render();

        } catch (error) {
            console.error('AI Character Generation Error:', error);
            ui.notifications.error(`Failed to generate character: ${error.message}`);
        } finally {
            button.prop('disabled', false).text('Generate Character');
        }
    }

    async _onCreateCharacter(event) {
        event.preventDefault();

        if (!this.characterData) {
            ui.notifications.warn('No character data to create. Generate a character first.');
            return;
        }

        try {
            // Create the actor
            const actorData = this.characterData.actor;
            const actor = await Actor.create(actorData);

            if (!actor) {
                throw new Error('Failed to create actor');
            }

            ui.notifications.info(`Created character: ${actor.name}`);

            // Create items
            if (this.characterData.items && this.characterData.items.length > 0) {
                const items = await actor.createEmbeddedDocuments('Item', this.characterData.items);
                ui.notifications.info(`Added ${items.length} items to ${actor.name}`);
            }

            // Apply post-processing
            const adapter = game.aiGM.adapterRegistry.getActive();
            if (adapter) {
                await adapter.postProcess(actor);
            }

            // Open the character sheet
            actor.sheet.render(true);

            // Clear form and close
            this.characterData = null;
            this.validationErrors = [];
            this.close();

        } catch (error) {
            console.error('Character Creation Error:', error);
            ui.notifications.error(`Failed to create character: ${error.message}`);
        }
    }

    async _onViewBlueprint(event) {
        event.preventDefault();

        const form = this.element.find('form')[0];
        const formData = new FormData(form);
        const actorType = formData.get('actorType') || 'character';

        const blueprint = game.aiGM.blueprintGenerator.generateAIBlueprint(actorType);

        // Display in console and show dialog
        console.log('Blueprint for', actorType, blueprint);

        new Dialog({
            title: `Blueprint: ${actorType}`,
            content: `<pre style="max-height: 400px; overflow-y: auto;">${JSON.stringify(blueprint, null, 2)}</pre>`,
            buttons: {
                copy: {
                    label: 'Copy to Clipboard',
                    callback: () => {
                        navigator.clipboard.writeText(JSON.stringify(blueprint, null, 2));
                        ui.notifications.info('Blueprint copied to clipboard');
                    }
                },
                close: {
                    label: 'Close'
                }
            }
        }).render(true);
    }

    async _onExportJSON(event) {
        event.preventDefault();

        if (!this.characterData) {
            ui.notifications.warn('No character data to export. Generate a character first.');
            return;
        }

        const json = JSON.stringify(this.characterData, null, 2);

        // Copy to clipboard
        navigator.clipboard.writeText(json);
        ui.notifications.info('Character data copied to clipboard');

        // Also show in console
        console.log('Character Data:', this.characterData);
    }
}

/**
 * Simple HTML template fallback if template file doesn't exist
 */
export function getCharacterGeneratorHTML() {
    return `
<form class="ai-character-generator">
    <div class="form-group">
        <label for="actorType">Character Type:</label>
        <select name="actorType" id="actorType">
            {{#each actorTypes}}
            <option value="{{this}}">{{lookup ../actorTypeLabels this}}</option>
            {{/each}}
        </select>
    </div>

    <div class="form-group">
        <label for="language">Language:</label>
        <select name="language" id="language">
            <option value="en">English</option>
            <option value="es">Español</option>
            <option value="fr">Français</option>
            <option value="de">Deutsch</option>
        </select>
    </div>

    <div class="form-group">
        <label for="prompt">Character Description:</label>
        <textarea name="prompt" id="prompt" rows="4" placeholder="Describe the character you want to create..."></textarea>
        <p class="hint">Example: "A brave elven ranger who protects the forest" or "Un detective cínico con un pasado oscuro"</p>
    </div>

    <div class="form-group">
        <label>System: {{systemName}} ({{systemId}})</label>
        <p class="hint">Adapter: {{adapterName}}</p>
    </div>

    <div class="button-row">
        <button type="button" id="generate-character" class="generate-btn">
            <i class="fas fa-magic"></i> Generate Character
        </button>
        <button type="button" id="view-blueprint" class="blueprint-btn">
            <i class="fas fa-file-code"></i> View Blueprint
        </button>
    </div>

    {{#if characterData}}
    <div class="generated-character">
        <h3>Generated Character: {{characterData.actor.name}}</h3>
        
        {{#if validationErrors.length}}
        <div class="validation-errors">
            <h4>Validation Warnings:</h4>
            <ul>
                {{#each validationErrors}}
                <li><strong>{{this.field}}:</strong> {{this.message}}</li>
                {{/each}}
            </ul>
        </div>
        {{/if}}

        <div class="button-row">
            <button type="button" id="create-character" class="create-btn">
                <i class="fas fa-user-plus"></i> Create in Foundry
            </button>
            <button type="button" id="export-json" class="export-btn">
                <i class="fas fa-download"></i> Export JSON
            </button>
        </div>
    </div>
    {{/if}}
</form>

<style>
    .ai-character-generator .form-group {
        margin-bottom: 1rem;
    }
    
    .ai-character-generator label {
        display: block;
        font-weight: bold;
        margin-bottom: 0.25rem;
    }
    
    .ai-character-generator input,
    .ai-character-generator select,
    .ai-character-generator textarea {
        width: 100%;
        padding: 0.5rem;
        border: 1px solid #7a7971;
        background: rgba(0, 0, 0, 0.05);
    }
    
    .ai-character-generator .hint {
        font-size: 0.85rem;
        font-style: italic;
        color: #777;
        margin-top: 0.25rem;
    }
    
    .ai-character-generator .button-row {
        display: flex;
        gap: 0.5rem;
        margin-top: 1rem;
    }
    
    .ai-character-generator button {
        flex: 1;
        padding: 0.5rem 1rem;
        cursor: pointer;
    }
    
    .validation-errors {
        background: #fff3cd;
        border: 1px solid #ffc107;
        padding: 0.5rem;
        margin: 1rem 0;
        border-radius: 4px;
    }
    
    .validation-errors ul {
        margin: 0.5rem 0 0 1.5rem;
    }
</style>
`;
}

