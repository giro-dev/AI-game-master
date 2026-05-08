# AI Game Master - Copilot Instructions

> For project overview and full conventions, see [AGENTS.md](../AGENTS.md) in the project root.

## Tech Stack
- **Backend:** Java 21, Spring Boot 3.5, Spring AI 1.1, Gradle, Lombok, PostgreSQL, OpenSearch
- **Frontend:** TypeScript (strict), Foundry VTT v12 module, ES2020

## Conventions
- Use Lombok annotations (`@Data`, `@Builder`, `@Getter`, `@Setter`) for Java classes.
- Controllers are `@RestController` under `/api/*`.
- Services use Spring AI `ChatClient` and `Advisor` APIs for LLM interactions.
- Use `record` types for immutable DTOs.
- Prompt templates in `src/main/resources/prompts/*.txt` use `{placeholder}` syntax.
- TypeScript uses strict mode with `noImplicitAny` and `strictNullChecks`.
- Foundry VTT ambient types are in `src/types/foundry-ambient.d.ts`.
- Default narration language is Catalan; prompts adapt to configured language.
- All structured LLM responses must use JSON format with explicit schemas in prompts.
- Configuration lives in `application.yml` under semantic prefixes.
- Never hardcode API keys; use environment variables.

## Build Commands
```bash
cd master-server && ./gradlew build      # Build backend
cd master-server && ./gradlew test       # Run tests
cd master-foundry && npm run build       # Compile TypeScript
```
