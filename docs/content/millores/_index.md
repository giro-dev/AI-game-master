---
title: "Punts de millora"
description: "Auditoria de simplificació: stack, codi mort, model routing i unificació de tools/agents."
weight: 30
---

Aquesta pàgina recull els punts de millora detectats mentre es reescrivia la documentació.
Cada punt indica si és un **fet verificat** al codi (amb l'enllaç al fitxer) o una **recomanació**.
Res d'això està implementat: és una proposta de treball.

{{< callout >}}
**Llegenda** — 🔎 *Verificat*: comprovat al codi de `main`. 💡 *Recomanació*: proposta de canvi, amb pros i contres.
⚠️ *Hipòtesi*: probable, però cal confirmar-ho executant el sistema.
{{< /callout >}}

## Resum executiu

| # | Àrea | Proposta | Esforç | Impacte |
|---|------|----------|--------|---------|
| 1 | Codi mort | Esborrar el panell antic, `/api/pdf`, DTOs buits i prompts orfes | Baix | Menys confusió, menys codi a mantenir |
| 2 | Model routing | Fer servir realment `fallback-model` i passar-hi **tots** els `ChatClient` | Baix | Resiliència i configuració única |
| 3 | Tools/agents | Un sol `AgentRunner` + definicions declaratives + 3 *toolsets* | Mitjà | 15 `ChatClient` → 1 punt d'entrada |
| 4 | Protocol | REST per a ordres, **una** cua STOMP per sessió per a esdeveniments | Mitjà | Client i servidor més simples |
| 5 | Stack | OpenSearch → **pgvector** al PostgreSQL existent | Mitjà | −2 contenidors, −1 client deprecat |
| 6 | Stack | Veu (Whisper/Piper) com a perfil opcional de Docker Compose | Baix | Arrencada sense GPU |
| 7 | Operació | Flyway, CI, autenticació mínima, higiene del repo | Baix–Mitjà | Seguretat i reproductibilitat |

## 1. Codi mort i duplicat

### 1.1 Panell monolític antic (frontend) 🔎

[`ai-game-master-panel.ts`](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/src/ui/ai-game-master-panel.ts) (168 línies)
i [`ai-game-master-panel.hbs`](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/templates/ai-game-master-panel.hbs) (684 línies)
ja no s'instancien: [`main.ts`](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/src/main.ts) crea sis finestres independents
(`GenerateApplication`, `SessionApplication`, `LibraryApplication`, `ConfigApplication`, `FeaturesApplication`, `TranscriptionApplication`)
i no importa `AIGameMasterPanel`. Tot i així, la plantilla i el CSS encara es declaren a
[`module.json`](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/module.json), i el comentari de capçalera de `main.ts`
(“All UI lives in AIGameMasterPanel”) i la DeepWiki encara el descriuen com l'orquestrador principal.

💡 Esborrar el fitxer `.ts`, la plantilla i el CSS (si no comparteix estils), treure'ls del manifest i actualitzar el comentari.

### 1.2 Dues vies d'ingesta de llibres 🔎

| Via | Endpoint | Pipeline | Qui la fa servir |
|-----|----------|----------|------------------|
| Nova | `POST /api/books/upload` ([`BookController`](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/controller/BookController.java)) | `IngestionPipeline`: parse → classificació IA → extracció d'entitats → emmagatzematge, amb progrés per WebSocket | El mòdul Foundry |
| Antiga | `POST /api/pdf/upload` ([`PdfUploadController`](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/controller/PdfUploadController.java)) | `PdfProcessingService`: només *split* + embeddings amb *rate limit* propi | Ningú al frontend |

💡 Eliminar `PdfUploadController` i `PdfProcessingService` (o convertir l'endpoint en un àlies de la via nova). Si el *rate limiting* de
`PdfProcessingService` és útil, moure'l a `IngestionPipeline`.

### 1.3 Fitxers buits i prompts orfes 🔎

- Sis fitxers Java de 0 bytes: `dto/RagContextRequest.java`, `dto/MetadataField.java`, `dto/RagSearchResponse.java`,
  `dto/BookUploadRequest.java`, `dto/PromptRequest.java`, `pdf_extractor/Section.java`.
- Tres prompts a [`resources/prompts/`](https://github.com/giro-dev/AI-game-master/tree/main/master-server/src/main/resources/prompts)
  que cap classe referencia: `character_generation_system.txt`, `npc_generation_system.txt`, `system_learning_system.txt`.
  (Els agents de creació de personatges i `SystemProfileService` construeixen el prompt *inline* al codi Java.)
- [`TranscriptionQueueService`](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/TranscriptionQueueService.java)
  no és injectat per cap altra classe.

💡 Esborrar-los, o bé (millor per als prompts) moure els prompts *inline* als fitxers `.txt` existents perquè tots els prompts visquin al mateix lloc.

### 1.4 Dos generadors d'objectes 🔎

- [`ItemGenerationService`](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/ItemGenerationService.java):
  generació d'objectes solts per a un compendi (via `/app/item/generate`), prompt a `item_generation_system.txt`, idioma fixat a `"en"`.
- [`ItemGenerationAgent`](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/ItemGenerationAgent.java):
  objectes de l'equipament d'un personatge, prompt *inline*, eines RAG.

Tots dos fan servir el mateix perfil de routing `item-generator`. 💡 Fusionar-los en un únic agent amb dos modes (`forCompendium`, `forCharacter`).

### 1.5 Dues vies de transcripció per WebSocket 🔎 / ⚠️

- `/app/audio/transcribe` fa `(byte[]) message.getPayload()` sobre un missatge STOMP JSON
  ([`WebSocketController`](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/controller/WebSocketController.java)).
  ⚠️ Amb el convertidor JSON, el *payload* arriba com a `String`/`Map`, de manera que és probable que aquest *cast* falli; el frontend no l'utilitza.
- `/app/adventure/transcription` accepta text ja transcrit o `audioBase64` i és la via real.

💡 Eliminar `/app/audio/transcribe`.

## 2. Model routing

El [`ModelRoutingService`](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/ModelRoutingService.java)
és una bona idea (un perfil per operació a `application.yml`), però està a mig fer.

### 2.1 Camps configurats que no es llegeixen 🔎

Cada perfil defineix `preferred-model`, `fallback-model`, `temperature`, `expected-format`, `max-tokens` i `latency-budget-ms`.
Al codi només s'apliquen el model preferit, la temperatura i els *tokens*:

- `fallback-model` **no es fa servir enlloc**: si el model preferit falla, no hi ha reintent amb el de reserva.
- `expected-format` tampoc no es llegeix.
- `latency-budget-ms` només genera un *log* quan se supera.

💡 Implementar el *fallback* dins `ModelRoutingService.timed(...)` (reintent amb `fallback-model` en cas d'excepció o *timeout*),
o bé esborrar els camps del YAML perquè la configuració no prometi coses que no passen.

### 2.2 `ChatClient`s que se salten el routing 🔎

| Classe | Model fixat al codi |
|--------|---------------------|
| [`CharacterGenerationService`](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/CharacterGenerationService.java) (usat per `/gm/character/explain`) | `gpt-4o-mini` |
| [`DocumentClassifier`](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/pdf_extractor/DocumentClassifier.java) | `gpt-4.1-mini` |
| [`SystemProfileService`](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/SystemProfileService.java) | `gpt-4.1-mini` |

A més, `GameMasterService` (xat de sessió) reutilitza el perfil `manual-answer`. 💡 Afegir perfils `character-explain`,
`chunk-classifier`, `system-learning` i `session-assistant` i fer servir `optionsFor(...)` a tot arreu.

## 3. Unificar tools i agents

### 3.1 Situació actual 🔎

Avui “agent” és només una convenció de noms. Hi ha **15 classes** que fan `chatClientBuilder.defaultOptions(...).build()`,
cadascuna amb el seu estil de prompt (fitxer `.txt` o text *inline*), de sortida (`.entity(...)`, `.content()` + `ObjectMapper`) i d'idioma:

| Tipus | Classes |
|-------|---------|
| Anomenats `*Agent` | `ConceptAgent`, `FieldFillerAgent`, `ItemGenerationAgent` |
| Serveis que també són crides LLM | `IntentClassifierService`, `RollDecisionService`, `AdventureDirectorService`, `StateVerifierService`, `GameMasterService`, `GameMasterManualSolver`, `ItemGenerationService`, `AdventureIngestionService`, `SystemProfileService`, `DocumentClassifier`, `EntityExtractor`, `CharacterGenerationService` |
| `@Tool` exposades al model | `RAGService` (4 cerques), `GameMasterManualSolver.solveDoubt` (*agent-as-tool*: fa una segona crida LLM), `RollDecisionService` (també *agent-as-tool*), `AdventureStateTool` |

Conseqüències: el *timing*, el *fallback*, l'idioma per defecte i el format JSON s'han de recordar a cada classe; afegir un agent nou
vol dir copiar ~30 línies de *boilerplate*; i és difícil veure d'un cop d'ull quines eines té cada agent.

### 3.2 Proposta 💡

**a) Un únic executor.** Una classe `AgentRunner` amb una sola API:

```java
<T> T run(String agentId, Map<String, Object> params, Class<T> output);
```

que aplica, en un sol lloc: perfil de routing + *fallback*, *timing*, prompt des de `prompts/{agentId}.txt`, idioma per defecte,
*advisors* (memòria, RAG) i les eines declarades.

**b) Agents declaratius.** Ampliar el bloc `game-master.routing.operations` existent perquè cada operació declari també les seves eines:

```yaml
game-master:
  agents:
    director-stateful:
      prompt: adventure_director_system.txt
      model: { preferred: gpt-4.1-mini, fallback: gpt-4.1-nano, temperature: 0.7 }  # exemple
      advisors: [rag, memory]
      toolsets: [adventure]
    field-filler:
      prompt: field_filler_system.txt   # avui és inline; es mouria a fitxer
      toolsets: [knowledge]
```

**c) Tres *toolsets* en lloc d'eines disperses.**

| Toolset | Eines | Origen actual |
|---------|-------|---------------|
| `knowledge` | cerca de regles, lore, bestiari, entitats; resposta de manual | `RAGService`, `GameMasterManualSolver` |
| `adventure` | llegir escena/NPC/pistes; decidir tirada | `AdventureStateTool`, `RollDecisionService` |
| `foundry` | accions VTT (tirades, dany, tokens) | avui és un camp `actions` dins la resposta JSON del director |

**d) Menys *agent-as-tool*.** `solveDoubt` fa una segona crida LLM dins d'una crida LLM; per al director n'hi ha prou amb la cerca RAG
(el model ja redacta). Mantenir `GameMasterManualSolver` només com a endpoint `/gm/resolve`.

**e) Pipeline de creació en un sol agent.** `ConceptAgent` → `FieldFillerAgent` → `ItemGenerationAgent` es poden mantenir com a passos,
però executats pel mateix `AgentRunner` (canvien només `agentId` i paràmetres).

**Resultat esperat:** un sol punt per configurar models, idioma i eines; afegir un agent = un prompt + 5 línies de YAML.

### 3.3 Instruccions per a assistents de codi 🔎

`AGENTS.md`, `CLAUDE.md`, `.github/copilot-instructions.md` i `.agents/skills/*` repeteixen stack i ordres.
`CLAUDE.md` ja remet a `AGENTS.md`. 💡 Fer el mateix amb `copilot-instructions.md` i deixar `AGENTS.md` com a font única;
aquesta web pot ser la documentació humana i `AGENTS.md` enllaçar-hi.

## 4. Protocol frontend ↔ backend

### 4.1 Massa cues i transports barrejats 🔎

- Prefixos d'URL inconsistents: `/api/books`, `/api/pdf`, `/api/system-profile` vs `/gm`, `/gm/character`, `/adventure`, `/audio`.
- Cues STOMP per tipus: `/queue/{id}`, `/queue/character-{id}`, `/queue/item-{id}`, `/queue/image-{id}`, `/queue/ingestion-{id}`, `/queue/adventure-{id}`.
- Patrons diferents per a coses semblants: personatges = REST + progrés per WS; objectes = només WS; aventures = REST + WS.
- SockJS i STOMP es carreguen des de **jsDelivr** a `module.json`; el mòdul no funciona sense Internet, tot i que el servidor també
  exposa un endpoint `/ws` natiu.

💡 Regla única: **REST (`/api/v1/...`) per a ordres, una sola cua `/queue/session-{id}` per a esdeveniments** (el camp `type` de
`WebSocketMessage` ja distingeix el tipus). Empaquetar el client STOMP (`@stomp/stompjs`) amb el mòdul i eliminar SockJS.

## 5. Stack més senzill

### 5.1 OpenSearch → pgvector 💡

Avui calen **PostgreSQL** (estat) + **OpenSearch** + **OpenSearch Dashboards** (vectors), més la dependència
`opensearch-rest-high-level-client`, que està deprecada.

PostgreSQL 17 ja hi és. Spring AI té `spring-ai-starter-vector-store-pgvector`, i el codi fa servir l'abstracció `VectorStore` i
`filterExpression` portables, així que el canvi hauria de quedar bàsicament a configuració.

| Pros | Contres |
|------|---------|
| −2 contenidors (i ~1–2 GB de RAM a local) | Cal reingerir els llibres |
| Una sola base de dades per fer còpies de seguretat | Es perd la cerca híbrida/BM25 d'OpenSearch (avui no s'utilitza) |
| Transaccions entre estat i vectors | Cal verificar l'equivalència de les expressions de filtre |

### 5.2 Veu opcional 💡

[`docker-compose.yml`](https://github.com/giro-dev/AI-game-master/blob/main/master-server/docker-compose.yml) aixeca Whisper amb la imatge
`latest-cuda` (necessita GPU NVIDIA) i Spring Boot arrenca **tots** els serveis (`spring.docker.compose.enabled: true`). Proposta:

- Posar `whisper` i `piper-tts` en un `profile: voice` de Compose (i Dashboards en `profile: debug`).
- Oferir transcripció per l'API d'OpenAI com a alternativa sense GPU (TTS ja té les dues opcions: Piper i OpenAI).
- Fixar versions d'imatge en lloc de `latest`.

Amb 5.1 + 5.2 l'arrencada mínima queda en **PostgreSQL + servidor**.

### 5.3 Esquema de base de dades 💡

`spring.jpa.hibernate.ddl-auto: update` evoluciona l'esquema sol, però no esborra columnes ni deixa historial.
Afegir **Flyway** amb una migració inicial a partir de l'esquema actual.

## 6. Operació, seguretat i higiene

- 🔎 **Sense autenticació** i amb `@CrossOrigin(origins = "*")` a tots els controladors i `setAllowedOriginPatterns("*")` al WebSocket.
  Correcte per a ús local; perillós si el servidor s'exposa a Internet (qualsevol pot gastar la clau d'OpenAI).
  💡 Un *token* compartit configurable al mòdul Foundry i comprovat per un filtre.
- 🔎 **Sense CI**: `.github/` només conté `copilot-instructions.md`. Hi ha 5 classes de test al backend.
  💡 Workflow que executi `./gradlew test` i `npm run build` a cada PR.
- 🔎 **Idioma**: el valor per defecte és català, però `ItemGenerationService` fixa `"en"` i la creació de personatges agafa `"en"` si no se n'indica cap.
  💡 Llegir sempre `game-master.chat.default-language`.
- 🔎 **Higiene del repo**: `.idea/` i `game-master.iml` estan versionats; no hi ha `README.md` a l'arrel ni `.gitignore` arrel.

## Full de ruta proposat

1. **Neteja (1–2 dies):** punt 1 sencer, 2.2, idioma, CI, `.gitignore`.
2. **Routing i agents (≈1 setmana):** implementar *fallback* (2.1), introduir `AgentRunner` i migrar-hi els agents un a un (3.2).
3. **Protocol (≈1 setmana):** `/api/v1`, cua única per sessió, client STOMP empaquetat (4.1).
4. **Stack (≈1 setmana):** pgvector + perfils de Compose + Flyway (5.x).
