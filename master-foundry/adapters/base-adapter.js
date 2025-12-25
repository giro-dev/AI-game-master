// Base System Adapter - Abstract interface for system-specific rules and validation
// Concrete adapters extend this to provide system-specific logic without polluting the core

export class BaseSystemAdapter {
    constructor() {
        this.systemId = game.system.id;
    }

    // Check if this adapter is compatible with the current system
    isCompatible() {
        throw new Error('isCompatible() must be implemented by subclass');
    }

    // Enhance a blueprint with system-specific constraints and metadata
    enhanceBlueprint(blueprint) {
        // Override in subclass to add system-specific constraints
        // Example: blueprint.constraints.push({ type: 'pointBudget', path: 'system.attributes', total: 27 })
    }

    // Validate character data with system-specific rules
    validate(characterData, blueprint) {
        // Override in subclass for system-specific validation
        return [];
    }

    // Derive computed values (e.g., ability modifiers, HP, AC)
    derive(actor) {
        // Override in subclass to compute derived values
        // Example: calculate ability modifiers from ability scores
    }

    // Post-process actor after creation (final cleanup, derived values)
    async postProcess(actor) {
        // Override in subclass for post-creation processing
        // Example: create default items, set computed fields
    }

    // Check if an item type is relevant for a given actor type
    isItemTypeRelevant(actorType, itemType) {
        // Default: all items are relevant
        // Override to filter out irrelevant item types
        return true;
    }

    // Get system-specific AI instructions to append to prompts
    getAIInstructions() {
        return '';
    }

    // Transform AI-generated data before validation (optional preprocessing)
    preprocessAIData(characterData) {
        return characterData;
    }

    // Get display name for this adapter
    getName() {
        return this.constructor.name;
    }
}

// Adapter Registry - Manages system adapters
export class AdapterRegistry {
    constructor() {
        this.adapters = new Map();
        this.activeAdapter = null;
    }

    // Register a system adapter
    register(adapter) {
        if (!(adapter instanceof BaseSystemAdapter)) {
            throw new Error('Adapter must extend BaseSystemAdapter');
        }

        this.adapters.set(adapter.systemId, adapter);
        console.log(`[AI-GM] Registered system adapter: ${adapter.getName()} for ${adapter.systemId}`);

        // Auto-activate if compatible with current system
        if (adapter.isCompatible() && !this.activeAdapter) {
            this.activate(adapter.systemId);
        }
    }

    // Activate an adapter by system ID
    activate(systemId) {
        const adapter = this.adapters.get(systemId);

        if (!adapter) {
            console.warn(`[AI-GM] No adapter found for system: ${systemId}`);
            return false;
        }

        if (!adapter.isCompatible()) {
            console.warn(`[AI-GM] Adapter for ${systemId} is not compatible with current system`);
            return false;
        }

        this.activeAdapter = adapter;
        console.log(`[AI-GM] Activated system adapter: ${adapter.getName()}`);
        return true;
    }

    // Get the active adapter
    getActive() {
        return this.activeAdapter;
    }

    // Get adapter by system ID
    get(systemId) {
        return this.adapters.get(systemId) || null;
    }

    // Check if an adapter is registered for the current system
    hasAdapterForCurrentSystem() {
        return this.adapters.has(game.system.id);
    }

    // List all registered adapters
    listAdapters() {
        return Array.from(this.adapters.values()).map(adapter => ({
            systemId: adapter.systemId,
            name: adapter.getName(),
            compatible: adapter.isCompatible(),
            active: adapter === this.activeAdapter
        }));
    }
}

