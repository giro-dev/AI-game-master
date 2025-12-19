/**
}
    }
        }));
            active: adapter === this.activeAdapter
            compatible: adapter.isCompatible(),
            name: adapter.getName(),
            systemId: adapter.systemId,
        return Array.from(this.adapters.values()).map(adapter => ({
    listAdapters() {
     */
     * @returns {Array<Object>}
     * List all registered adapters
    /**

    }
        return this.adapters.has(game.system.id);
    hasAdapterForCurrentSystem() {
     */
     * @returns {boolean}
     * Check if an adapter is registered for the current system
    /**

    }
        return this.adapters.get(systemId) || null;
    get(systemId) {
     */
     * @returns {BaseSystemAdapter|null}
     * @param {string} systemId
     * Get adapter by system ID
    /**

    }
        return this.activeAdapter;
    getActive() {
     */
     * @returns {BaseSystemAdapter|null}
     * Get the active adapter
    /**

    }
        return true;
        console.log(`[AI-GM] Activated system adapter: ${adapter.getName()}`);
        this.activeAdapter = adapter;

        }
            return false;
            console.warn(`[AI-GM] Adapter for ${systemId} is not compatible with current system`);
        if (!adapter.isCompatible()) {

        }
            return false;
            console.warn(`[AI-GM] No adapter found for system: ${systemId}`);
        if (!adapter) {

        const adapter = this.adapters.get(systemId);
    activate(systemId) {
     */
     * @param {string} systemId - The system ID
     * Activate an adapter by system ID
    /**

    }
        }
            this.activate(adapter.systemId);
        if (adapter.isCompatible() && !this.activeAdapter) {
        // Auto-activate if compatible with current system

        console.log(`[AI-GM] Registered system adapter: ${adapter.getName()} for ${adapter.systemId}`);
        this.adapters.set(adapter.systemId, adapter);

        }
            throw new Error('Adapter must extend BaseSystemAdapter');
        if (!(adapter instanceof BaseSystemAdapter)) {
    register(adapter) {
     */
     * @param {BaseSystemAdapter} adapter - The adapter to register
     * Register a system adapter
    /**

    }
        this.activeAdapter = null;
        this.adapters = new Map();
    constructor() {
export class AdapterRegistry {
 */
 * Adapter Registry - Manages system adapters
/**

}
    }
        return this.constructor.name;
    getName() {
     */
     * @returns {string}
     * Get display name for this adapter
    /**

    }
        return characterData;
    preprocessAIData(characterData) {
     */
     * @returns {Object} Transformed data
     * @param {Object} characterData - Raw data from AI
     * Transform AI-generated data before validation (optional preprocessing)
    /**

    }
        return '';
    getAIInstructions() {
     */
     * @returns {string}
     * Get system-specific AI instructions to append to prompts
    /**

    }
        return true;
        // Override to filter out irrelevant item types
        // Default: all items are relevant
    isItemTypeRelevant(actorType, itemType) {
     */
     * @returns {boolean}
     * @param {string} itemType - The item type to check
     * @param {string} actorType - The actor type
     * Check if an item type is relevant for a given actor type
    /**

    }
        // Example: create default items, set computed fields
        // Override in subclass for post-creation processing
    async postProcess(actor) {
     */
     * @param {Actor} actor - The newly created actor
     * Post-process actor after creation (final cleanup, derived values)
    /**

    }
        // Example: calculate ability modifiers from ability scores
        // Override in subclass to compute derived values
    derive(actor) {
     */
     * @param {Actor} actor - The Foundry actor to derive values for
     * Derive computed values (e.g., ability modifiers, HP, AC)
    /**

    }
        return [];
        // Override in subclass for system-specific validation
    validate(characterData, blueprint) {
     */
     * @returns {Array} Array of validation errors
     * @param {Object} blueprint - The blueprint being used
     * @param {Object} characterData - The character data to validate
     * Validate character data with system-specific rules
    /**

    }
        // Example: blueprint.constraints.push({ type: 'pointBudget', path: 'system.attributes', total: 27 })
        // Override in subclass to add system-specific constraints
    enhanceBlueprint(blueprint) {
     */
     * @param {Object} blueprint - The blueprint to enhance
     * Enhance a blueprint with system-specific constraints and metadata
    /**

    }
        throw new Error('isCompatible() must be implemented by subclass');
    isCompatible() {
     */
     * @returns {boolean}
     * Check if this adapter is compatible with the current system
    /**

    }
        this.systemId = game.system.id;
    constructor() {
export class BaseSystemAdapter {

 */
 * Concrete adapters extend this to provide system-specific logic without polluting the core
 * Base System Adapter - Abstract interface for system-specific rules and validation

