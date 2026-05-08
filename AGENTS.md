# AI Game Master - Agent Instructions

> Cross-tool agent configuration. Read by Codex, Copilot, Cursor, Windsurf, Devin, and other AI agents.

## Project Overview

AI-driven Game Master orchestration engine for tabletop RPGs. Integrates LLMs, RAG, and multi-modal audio into Foundry VTT for automated narration, character generation, and session management.

## Tech Stack

### Backend (`master-server/`)
- **Language:** Java 21
- **Framework:** Spring Boot 3.5.x with Spring AI 1.1.x
- **Build:** Gradle (Groovy DSL)
- **Database:** PostgreSQL 17 (via Spring Data JPA, `ddl-auto: update`)
- **Vector Store:** OpenSearch 2.19.x (for RAG / semantic search)
- **AI Models:** OpenAI (GPT-4.1 family), Google Gemini (commented out but supported)
- **TTS:** OpenAI gpt-4o-mini-tts or local Piper (Wyoming protocol)
- **STT:** Whisper (faster-whisper-server)
- **Annotations:** Lombok (`@Data`, `@Getter`, `@Setter`, etc.)

### Frontend (`master-foundry/`)
- **Language:** TypeScript (strict mode)
- **Target:** ES2020
- **Platform:** Foundry VTT module (v12)
- **Build:** `tsc` (no bundler)
- **Dependencies:** jQuery (Foundry built-in), Foundry VTT types

### Infrastructure
- **Docker Compose:** PostgreSQL, OpenSearch, OpenSearch Dashboards, Whisper, Piper TTS
- **Container:** Eclipse Temurin 21 (multi-stage Dockerfile)

## Project Structure

```
master-server/
  src/main/java/dev/agiro/masterserver/
    config/          # Spring configuration & properties classes
    controller/      # REST & WebSocket controllers
    dto/             # Data Transfer Objects
    entity/          # JPA entities
    model/           # Domain models
    repository/      # Spring Data repositories
    service/         # Business logic, AI agents, TTS/STT services
    pdf_extractor/   # PDF parsing for adventure books
  src/main/resources/
    application.yml  # All configuration (Spring, AI, TTS, audio, etc.)
    prompts/         # LLM prompt templates (*.txt with {placeholders})
  docker-compose.yml # Infrastructure services

master-foundry/
  src/
    main.ts                  # Module entry point
    websocket-client.ts      # WebSocket connection to master-server
    types/                   # TypeScript type definitions
    services/                # Audio capture/playback, character generation
    ui/                      # Foundry VTT panel UI
    schema/                  # Blueprint/schema extraction
    blueprints/              # Blueprint generator
    skills/                  # System skill adapters
    system-snapshot/         # Foundry state snapshot utilities
    utils/                   # Helpers (field-tree, session, sanitizer)
  templates/                 # Handlebars (HBS) templates for VTT UI
  styles/                    # CSS for the Foundry module
  module.json                # Foundry VTT module manifest
```

## Key Concepts

- **Blueprint:** JSON schema defining system-specific actor/item structures extracted from Foundry.
- **Reference Character:** Real Foundry actor used as structural template for AI generation.
- **System Knowledge Profile:** AI-learned understanding of a specific RPG ruleset via RAG.
- **Director Stateful:** Main narrative engine managing adventure flow, scene state, and tension.
- **Tension Level:** Numeric value (0-10) tracking narrative pressure during play.
- **Intent Classifier:** Determines player goal (action, dialogue, meta) with configurable model routing.
- **RAG Advisor:** Injects game rules from OpenSearch vector store into LLM context.
- **Roll Decision:** AI logic determining if a dice roll is required for a player action.
- **State Verifier:** Safety layer validating LLM-proposed game state changes.
- **Model Routing:** Per-operation AI model selection with preferred/fallback, temperature, and latency budgets.
- **Wyoming Protocol:** TCP protocol for communicating with local Piper TTS/STT services.

## Build & Run Commands

```bash
# Backend
cd master-server
./gradlew bootRun          # Start server (auto-starts Docker Compose services)
./gradlew build            # Build
./gradlew test             # Run tests
./gradlew bootJar          # Build fat JAR

# Frontend
cd master-foundry
npm install                # Install dependencies
npm run build              # Compile TypeScript
npm run watch              # Watch mode
```

## Coding Conventions

### Java (Backend)
- Use Lombok annotations (`@Data`, `@Builder`, `@Getter`, `@Setter`, `@AllArgsConstructor`, `@NoArgsConstructor`) to reduce boilerplate.
- Follow Spring Boot conventions: `@Service`, `@RestController`, `@Configuration`, `@ConfigurationProperties`.
- Controllers map to `/api/*` endpoints.
- Prompt templates go in `src/main/resources/prompts/` as `.txt` files with `{placeholder}` syntax.
- Configuration is centralized in `application.yml` under semantic prefixes (`game-master.*`, `whisper.*`, `piper-tts.*`, `tts.*`, `audio.*`).
- Use `record` types for DTOs when no mutation is needed.
- Services that interact with LLMs should use Spring AI's `ChatClient` and `Advisor` APIs.
- AI model routing is configured per-operation in `application.yml` under `game-master.routing.operations`.

### TypeScript (Frontend)
- Strict mode enabled (`strict: true`, `noImplicitAny`, `strictNullChecks`, `noImplicitReturns`).
- ES2020 target with ES module syntax.
- Use Foundry VTT API patterns (Hooks, game global, Actor/Item documents).
- Foundry ambient types are declared in `src/types/foundry-ambient.d.ts`.
- WebSocket communication with backend follows a defined message protocol.

### Prompts
- Prompt templates use `{variableName}` placeholder syntax.
- Prompts are multilingual (primarily Catalan, but language is configurable).
- All LLM responses that need structured data must request JSON output.
- Include explicit JSON schema in the prompt when expecting structured responses.

## Environment Variables

| Variable | Purpose |
|---|---|
| `OPENAI_API_KEY` | OpenAI API access |
| `GM-PROJECT` | OpenAI project ID |
| `GOOGLE_GENAI_API_KEY` | Google Gemini API access (when enabled) |

## Important Notes

- The default narration language is **Catalan** (`default-language: catalan`). Prompts and narration adapt to the configured language.
- Docker Compose services start automatically with Spring Boot (`spring.docker.compose.enabled: true`).
- The vector store uses 1536-dimension embeddings (OpenAI `text-embedding-3-small`) with cosine similarity.
- Audio files are stored temporarily at `${java.io.tmpdir}/ai-gm-audio` with a 60-minute TTL.
- Do not commit API keys or secrets. Use environment variables.
- Do not modify prompt templates without understanding the expected JSON response format.
