# System Skills — Per-System Adapters for AI-Game-Master

**Skills** are optional JSON configurations that fine-tune the AI-Game-Master module for specific game systems or worlds. The core module is fully **system-agnostic** and works via runtime schema introspection — skills merely overlay/augment the auto-detected information.

## When to Use Skills

| Scenario | Skill needed? |
|---|---|
| Standard system with TypeDataModel (e.g. D&D 5e, Pathfinder 2e, CoC7) | Optional — auto-detection works, but a skill can add constraints/hints |
| Custom System Builder world (e.g. Trueque) | **Yes** — CSB exposes no schema via Foundry APIs |
| Non-standard system with unusual data model | **Yes** — provides the missing schema fields |

## Quick Start

1. Open **AI Game Master → System tab → System Skills**
2. Click **Import Skill** and select a `.json` file (see examples below)
3. The skill activates immediately for the matching system/world

Or click **New Skill** to create one from scratch using the built-in JSON editor.

## Skill Structure

```jsonc
{
  // Identity
  "id": "my-skill",                    // Auto-generated if omitted
  "name": "My System Adapter",         // Required
  "description": "...",
  "systemId": "my-system",             // Required — must match game.system.id
  "worldId": "my-world",               // Optional — match a specific world
  "priority": 10,                      // Higher wins on conflicts
  "enabled": true,

  // Actor/Item type overrides
  "extraActorTypes": ["custom-type"],   // Append to auto-detected types
  "actorOverrides": {
    "character": {
      "addFields": [...],               // Extra FieldDefinitions
      "removeFields": ["system.foo"],   // Remove by path
      "patchFields": {                  // Partial merge by path
        "system.bar": { "min": 0, "max": 100 }
      }
    }
  },

  // Constraints for character validation
  "constraints": [
    { "type": "range", "fieldPath": "system.str", "description": "...", "parameters": { "min": 3, "max": 18 } }
  ],

  // AI generation hints
  "creationHints": "Free-text instructions for the AI...",
  "creationSteps": ["Step 1...", "Step 2..."],

  // Default items to create alongside a new actor
  "defaultItems": [
    { "name": "Torch", "type": "equipment", "system": { "quantity": 5 } }
  ],

  // Field path aliases (AI name → real path)
  "fieldAliases": {
    "strength": "system.attributes.str.value"
  }
}
```

## Examples

| File | System | Description |
|---|---|---|
| `coc7-skill.json` | CoC7 | Characteristic ranges, creation steps, skill point formulas |
| `custom-system-builder-trueque-skill.json` | custom-system-builder (world: trueque) | Full schema for a CSB-based Trueque world |

## Creating Your Own Skill

1. Start from one of the examples or use the **New Skill** button in the UI
2. Set `systemId` to match `game.system.id` (check in the Foundry console)
3. If the system uses Custom System Builder, also set `worldId`
4. Add fields via `actorOverrides.CHARACTER_TYPE.addFields`
5. Add constraints and AI hints as needed
6. Import the JSON file or paste it in the editor

## How It Works

The skill system integrates at two levels:

1. **SchemaExtractor**: Skills can add/remove/patch fields and add extra actor/item types
2. **BlueprintGenerator**: Skills inject constraints, creation hints, and default items into the blueprint sent to the AI backend

Skills are stored in Foundry module settings (`ai-gm.systemSkills`) and persist across sessions.
