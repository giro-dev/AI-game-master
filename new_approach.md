## Multi-Agent Architecture for Foundry VTT AI Module

This is a great use case for multi-agent design! Here's how I'd think about it:

---

### Core Architecture: Orchestrator + Specialist Agents

```
User Request
     │
     ▼
┌─────────────────┐
│   ORCHESTRATOR  │  ← Routes, plans, coordinates
│      AGENT      │
└────────┬────────┘
         │
    ┌────┴─────────────────────────────────────┐
    ▼           ▼            ▼           ▼      ▼
┌──────┐  ┌─────────┐  ┌────────┐  ┌──────┐  ┌──────┐
│LORE  │  │CHARACTER│  │COMBAT  │  │ITEM  │  │WORLD │
│AGENT │  │  AGENT  │  │ AGENT  │  │AGENT │  │AGENT │
└──────┘  └─────────┘  └────────┘  └──────┘  └──────┘
    │           │            │          │          │
    └───────────┴────────────┴──────────┴──────────┘
                             │
                    ┌────────▼────────┐
                    │  RAG / FOUNDRY  │
                    │  VTT DATA LAYER │
                    └─────────────────┘
```

---

### Agent Roles & Responsibilities

**🎯 Orchestrator Agent**
The brain. Receives natural language requests, decomposes them into sub-tasks, delegates to specialists, and synthesizes results.
- Intent classification (generate, query, modify, narrate)
- Dependency resolution (item must exist before equipping a character)
- Conflict resolution between agent outputs
- Session/context memory across a game session

---

**📖 Lore Agent** ← Your current RAG system, elevated
- Query the RAG for rules, spells, lore, monster stats
- Validate generated content against game system rules
- Answer GM/player questions in natural language
- Cross-reference between books (e.g. supplement vs core rules)

---

**🧙 Character Agent** ← Evolution of your current generator
- Full character sheet generation with stat balancing
- Level-up suggestions based on class/archetype
- Backstory generation coherent with active world lore
- NPC personality + relationship graph building
- Party composition analysis ("the party lacks a healer")

---

**⚔️ Combat Agent**
- Encounter design (CR balancing, terrain, tactics)
- Live combat assistance: suggest actions, track conditions
- Post-combat XP/loot distribution
- Boss/minion group generation with synergistic abilities

---

**🗡️ Item Agent** ← Evolution of your current generator
- Thematic item generation (fits character backstory, world tone)
- Magic item balancing against game system rules
- Shop inventory generation by region/faction
- Treasure hoard generation with contextual flavor

---

**🌍 World Agent** ← New, high value
- Location/dungeon generation (rooms, NPCs, encounters)
- Faction relationship management
- Timeline and event tracking
- Weather, economy, political state as context injectors

---

### Useful Shared Functions (Tool Layer)

These are callable by any agent via tool-use:

```
foundry_get_actor(id)           → pull live actor data
foundry_update_actor(id, delta) → patch actor fields
foundry_get_items(filter)       → query item compendium
foundry_create_entity(type, data)
foundry_get_scene_context()     → active scene, tokens, etc.

rag_query(query, collection, top_k)
rag_store(content, metadata, collection)
rag_get_game_system()           → returns schema/ruleset

validate_against_rules(entity, system)   → Lore Agent helper
compute_cr(encounter_config)             → Combat Agent helper
resolve_dependencies(entity_list)        → Orchestrator helper
summarize_session(event_log)             → World Agent helper
```

---

### Suggested Implementation Phases

| Phase | Focus |
|---|---|
| **1** | Orchestrator + existing agents (Character, Item, Lore) wired together |
| **2** | Combat Agent + encounter/initiative tools |
| **3** | World Agent + persistent world state in RAG |
| **4** | Inter-agent memory: agents share observations (e.g. Character Agent informs World Agent of PC actions) |

---

### Key Design Decisions to Make

- **Synchronous vs Async agents** — does the GM wait, or do agents work in background?
- **Agent memory scope** — per-session, per-campaign, or persistent across campaigns?
- **Conflict strategy** — if Lore Agent says "this item doesn't exist in this system" but Item Agent generated it, who wins?
- **Human-in-the-loop checkpoints** — especially for world-altering actions, you likely want GM approval before committing to Foundry

## Game System Abstraction Layer

This is genuinely the hardest problem in your architecture. The core challenge:

> Foundry VTT has ~300+ game systems, each with completely different actor/item schemas, and your backend needs to generate valid entities for any of them.

---

### The Mental Model: Schema as First-Class Citizen

Instead of trying to hardcode mappings, **the game system schema itself becomes a RAG document** — learned once per system, versioned, and used as generation context.

```
Foundry VTT                    Backend
─────────────────              ──────────────────────────────
Actor template       ──────►   Schema Extraction Pipeline
Item template        ──────►        │
System data model    ──────►        ▼
Active actor/item    ──────►   SystemProfile (stored in OpenSearch)
examples             ──────►        │
                                    ▼
                               Generation with
                               schema-aware prompts
```

---

### Phase 1 — Schema Extraction (Foundry Module Side)

Your module already reads actors/items. Extend it to introspect the **system itself**:

```javascript
class SystemSchemaExtractor {

  extractSystemProfile() {
    return {
      id:      game.system.id,          // "dnd5e", "pf2e", "shadowrun5e"
      version: game.system.version,
      
      actorTypes:  this.extractActorTypes(),
      itemTypes:   this.extractItemTypes(),
      rollMechanics: this.extractRollMechanics(),
      derived:     this.extractDerivedFields(),
    };
  }

  extractActorTypes() {
    // Foundry exposes the template.json structure
    return Object.entries(game.system.model.Actor)
      .map(([type, schema]) => ({
        type,
        fields:      this.flattenSchema(schema),
        required:    this.detectRequiredFields(schema),
        valueRanges: this.inferValueRanges(schema),
      }));
  }

  extractRollMechanics() {
    // THIS is the hard part — detect how rolls are triggered
    return {
      diceFormula:   this.detectDiceFormula(),   // "2d6", "1d20+mod", "nd6 hits"
      rollTriggers:  this.detectRollTriggers(),  // what fields drive a roll
      successModel:  this.detectSuccessModel(),  // target number vs count hits vs opposed
    };
  }

  detectRollTriggers() {
    // Inspect item types for action/trigger patterns
    const items = game.items.contents.slice(0, 20); // sample
    return items.flatMap(item => {
      const data = item.system;
      return this.findActionFields(data);  // fields named: actionType, roll, macro, etc.
    });
  }

  // Introspect a live actor to infer ranges
  inferValueRanges(schema, sampleSize = 30) {
    const actors = game.actors.contents.slice(0, sampleSize);
    return Object.keys(schema).reduce((acc, field) => {
      const values = actors.map(a => getProperty(a.system, field))
                           .filter(v => v !== undefined);
      acc[field] = this.summarizeValues(values); // {min, max, type, enum?}
      return acc;
    }, {});
  }
}
```

---

### Phase 2 — The SystemProfile Document

What gets stored in OpenSearch per game system:

```typescript
interface SystemProfile {
  // Identity
  systemId:      string;
  version:       string;
  extractedAt:   number;
  confidence:    number;        // 0-1, based on sample size

  // Schema
  actorSchemas:  ActorSchema[];
  itemSchemas:   ItemSchema[];

  // ← THE CRITICAL ABSTRACTION
  semanticMap:   SemanticMap;
  rollMechanics: RollMechanics;

  // Examples (few-shot material for generation)
  actorExamples: Record<string, unknown>[];
  itemExamples:  Record<string, unknown>[];
}

interface SemanticMap {
  // Maps universal concepts → system-specific field paths
  health:        FieldMapping;   // "attributes.hp.value" in dnd5e
                                 // "attributes.hp.value" in pf2e  
                                 // "derived.currentPhysical" in sr5e

  primaryStats:  FieldMapping[];
  skills:        FieldMapping[];
  rollAttribute: FieldMapping;   // what field feeds the dice roll
  level:         FieldMapping;
  currency:      FieldMapping[];
}

interface FieldMapping {
  path:        string;           // dot-notation path in actor.system
  type:        "number" | "string" | "boolean" | "object";
  range?:      [number, number];
  required:    boolean;
  inferredAs:  SemanticConcept;  // the universal label we assigned
  confidence:  number;
}

interface RollMechanics {
  formula:      string;          // "1d20", "2d6", "Xd6"
  successModel: "target_number"  // beat a DC
              | "count_hits"     // Shadowrun, WoD
              | "opposed"        // roll vs roll
              | "pbta"           // 2d6 partial/full success
              | "unknown";
  
  modifierSource: string;        // field path that adds to roll
  skillAsItem:    boolean;       // true in PF2e — skills live on actor
                                 // false in SR5e — skills are item-like entities
}
```

---

### Phase 3 — Semantic Inference Engine (Spring Boot)

The hard problem: **automatically mapping unknown fields to universal concepts**.

```java
@Service
public class SemanticFieldInferenceService {

    // Heuristic + LLM hybrid approach
    public SemanticMap inferSemanticMap(RawSystemSchema schema,
                                        List<ActorSample> samples) {

        SemanticMap map = new SemanticMap();

        // Step 1: heuristic pass (fast, no LLM cost)
        map.merge(heuristicInference(schema));

        // Step 2: LLM pass for ambiguous or unknown fields
        List<String> ambiguous = map.getAmbiguousFields();
        if (!ambiguous.isEmpty()) {
            map.merge(llmInference(schema, samples, ambiguous));
        }

        // Step 3: validation against live samples
        map.setConfidence(validateAgainstSamples(map, samples));

        return map;
    }

    private SemanticMap heuristicInference(RawSystemSchema schema) {
        // Pattern matching on field names
        Map<String, SemanticConcept> patterns = Map.of(
            ".*\\.hp\\.value",         SemanticConcept.HEALTH,
            ".*\\.health\\.current",   SemanticConcept.HEALTH,
            ".*\\.stun\\.value",       SemanticConcept.HEALTH_SECONDARY,
            ".*\\.level$",             SemanticConcept.LEVEL,
            ".*\\.attributes\\.str.*", SemanticConcept.STAT_STRENGTH,
            ".*\\.skills\\..*\\.rank", SemanticConcept.SKILL_RANK
        );
        // ... match and return
    }

    private SemanticMap llmInference(RawSystemSchema schema,
                                      List<ActorSample> samples,
                                      List<String> ambiguous) {
        // Build a prompt with:
        // 1. The unknown field paths + their sampled values
        // 2. The universal concept vocabulary
        // 3. A few-shot example of a known system mapping
        String prompt = buildInferencePrompt(schema, samples, ambiguous);

        // Call Spring AI
        return chatClient.prompt(prompt)
                         .call()
                         .entity(SemanticMap.class);  // structured output
    }
}
```

The inference prompt does the heavy lifting:

```
You are mapping a Foundry VTT game system schema to universal RPG concepts.

UNIVERSAL CONCEPTS: health, health_secondary, level, stat_strength, 
stat_dexterity, skill_rank, roll_attribute, currency, initiative, 
armor_class, damage_formula, action_trigger ...

KNOWN MAPPING EXAMPLE (dnd5e):
  "system.attributes.hp.value" → health
  "system.abilities.str.value" → stat_strength
  "system.details.level"       → level

UNKNOWN SYSTEM FIELDS with sampled values:
  "system.derived.currentPhysical": [10, 8, 12, 9, 11]
  "system.derived.currentStun":     [8, 6, 10, 7, 9]
  "system.attributes.body.base":    [4, 3, 5, 4, 6]
  "system.attributes.agility.base": [3, 5, 4, 6, 3]

MAP each field to the closest universal concept.
Respond ONLY as JSON: { "fieldPath": "concept" }
```

---

### Phase 4 — Agnostic Generation Pipeline

Now generation becomes system-agnostic at the intent level:

```java
@Service
public class AgnosticActorGenerationService {

    public FoundryActor generateActor(ActorGenerationRequest request,
                                       SystemProfile profile) {

        // 1. Generate in universal intermediate format
        UniversalActor universal = generateUniversal(request, profile);

        // 2. Map universal → system-specific via SemanticMap
        Map<String, Object> systemData = mapToSystem(universal, profile.getSemanticMap());

        // 3. Fill non-semantic fields with defaults/examples
        systemData = fillDefaults(systemData, profile.getActorExamples());

        // 4. Validate completeness
        ValidationResult validation = validate(systemData, profile);

        return new FoundryActor(request.getType(), request.getName(), systemData);
    }

    private UniversalActor generateUniversal(ActorGenerationRequest req,
                                              SystemProfile profile) {
        // Prompt uses the profile's roll mechanics + semantic vocabulary
        // NOT system-specific field names
        String prompt = """
            Generate an RPG actor with these universal fields:
            - health: integer in range %s
            - primary_stats: map of stat→value, range %s
            - skills: list of {name, rank}
            - roll_mechanic: %s
            
            Concept: %s
            Tone: %s
            """.formatted(
                profile.getHealthRange(),
                profile.getStatRange(),
                profile.getRollMechanics().getFormula(),
                req.getConcept(),
                req.getTone()
            );

        return chatClient.prompt(prompt).call().entity(UniversalActor.class);
    }
}
```

---

### The Learning Loop

Systems improve over time without manual work:

```
GM uses module
      │
      ├── accepts generated actor    → positive signal
      ├── edits generated actor      → diff stored as correction
      └── rejects generated actor    → negative signal + reason

      ▼
FeedbackService.ingestCorrection(diff, systemId)
      │
      ▼
Re-score SemanticMap confidence
      │
      ▼
If confidence drops below threshold:
   → trigger re-extraction with more samples
   → or flag system as "needs review" in OpenSearch
```

---

### Summary of What to Build

| Component | Where | Priority |
|---|---|---|
| `SystemSchemaExtractor` | Foundry module | 🔴 First |
| `SystemProfile` document + OpenSearch index | Backend | 🔴 First |
| `SemanticFieldInferenceService` (heuristic pass) | Spring Boot | 🔴 First |
| LLM inference pass for ambiguous fields | Spring Boot AI | 🟡 Second |
| `AgnosticActorGenerationService` | Spring Boot | 🟡 Second |
| Feedback/correction loop | Both | 🟢 Third |

The heuristic pass alone will cover ~70% of popular systems. The LLM inference handles the exotic ones. The feedback loop handles drift as systems release updates.

Want me to go deeper on the OpenSearch index design for `SystemProfile`, or the Spring AI structured output for the inference step?