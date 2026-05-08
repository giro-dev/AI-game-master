---
name: ai-game-master-development
description: Development workflow for the AI Game Master project. Covers building, running, and testing both the Spring Boot backend and the Foundry VTT TypeScript frontend.
---

# AI Game Master - Development Skill

## Prerequisites

- Java 21 (Eclipse Temurin recommended)
- Node.js 18+ and npm
- Docker and Docker Compose (for PostgreSQL, OpenSearch, Whisper, Piper)
- Environment variables: `OPENAI_API_KEY`, optionally `GOOGLE_GENAI_API_KEY`

## Backend (master-server)

### Setup & Run
1. Navigate to `master-server/`
2. Run `./gradlew bootRun` — this auto-starts Docker Compose services (PostgreSQL, OpenSearch, etc.)
3. Server starts on port 8080 by default

### Build
```bash
cd master-server && ./gradlew build
```

### Test
```bash
cd master-server && ./gradlew test
```

### Docker Build
```bash
cd master-server && docker build -t ai-game-master .
```

## Frontend (master-foundry)

### Setup
```bash
cd master-foundry && npm install
```

### Build
```bash
cd master-foundry && npm run build
```

### Watch Mode
```bash
cd master-foundry && npm run watch
```

## Infrastructure Services

Docker Compose (`master-server/docker-compose.yml`) provides:

| Service | Port | Purpose |
|---------|------|---------|
| PostgreSQL | 5432 | Game state persistence (user: `gamemaster`, pass: `gamemaster`, db: `gamemaster`) |
| OpenSearch | 9200 | Vector store for RAG |
| OpenSearch Dashboards | 5601 | OpenSearch UI |
| Whisper | 9300 | Speech-to-text |
| Piper TTS | 10200 | Text-to-speech (Wyoming protocol) |

## Key Configuration

All configuration is in `master-server/src/main/resources/application.yml`:
- `game-master.chat.*` — Default model and language
- `game-master.routing.operations.*` — Per-operation AI model routing
- `whisper.*` — STT configuration
- `piper-tts.*` / `openai-tts.*` / `tts.*` — TTS provider configuration
- `audio.store.*` — Audio file storage settings

## Common Tasks

### Adding a new LLM prompt
1. Create a `.txt` file in `src/main/resources/prompts/`
2. Use `{variableName}` syntax for placeholders
3. Load it in the service via Spring's `@Value("classpath:prompts/your_prompt.txt")`

### Adding a new REST endpoint
1. Create or modify a controller in `controller/` package
2. Use `@RestController` and `@RequestMapping("/api/...")`
3. Inject services via constructor (Lombok `@RequiredArgsConstructor`)

### Adding a new AI operation with model routing
1. Add configuration in `application.yml` under `game-master.routing.operations.your-operation`
2. Define `preferred-model`, `fallback-model`, `temperature`, `expected-format`, `max-tokens`, `latency-budget-ms`
3. Use `ModelRoutingService` in your service to get the appropriate model
