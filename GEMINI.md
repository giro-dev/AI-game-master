# AI Game Master - Gemini CLI Instructions

> For project overview and full conventions, see [AGENTS.md](./AGENTS.md).

## Quick Reference

- **Backend:** Java 21 + Spring Boot 3.5 + Spring AI 1.1 + Gradle (`master-server/`)
- **Frontend:** TypeScript strict + Foundry VTT module (`master-foundry/`)
- **Infra:** Docker Compose (PostgreSQL, OpenSearch, Whisper, Piper TTS)

## Commands

```bash
# Backend
cd master-server && ./gradlew bootRun    # Run (auto-starts Docker services)
cd master-server && ./gradlew test       # Test
cd master-server && ./gradlew build      # Build

# Frontend
cd master-foundry && npm run build       # Compile TS
cd master-foundry && npm run watch       # Watch mode
```

## Key Patterns

- Lombok everywhere: `@Data`, `@Builder`, `@Getter`, `@Setter`
- Prompt templates: `src/main/resources/prompts/*.txt` with `{placeholder}` syntax
- Config: `application.yml` under `game-master.*`, `whisper.*`, `piper-tts.*`, `tts.*`
- AI model routing: per-operation config with preferred/fallback models
- All LLM structured responses use JSON format
- Default language is Catalan; prompts are multilingual
- Foundry VTT types: `src/types/foundry-ambient.d.ts`
- Never commit API keys; use env vars (`OPENAI_API_KEY`, `GOOGLE_GENAI_API_KEY`)
