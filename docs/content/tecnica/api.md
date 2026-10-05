---
title: "API"
description: "Rutes REST, missatges STOMP i format dels events."
weight: 30
---

## REST

| Mètode | Ruta | Controlador | Propòsit |
|---|---|---|---|
| `POST` | `/gm/respond` | `GameMasterController` | Envia una pregunta o acció al Game Master. |
| `GET` | `/gm/resolve` | `GameMasterManualController` | Consulta el solucionador de manuals (`query`, `topK`, sistema i document opcionals). |
| `POST` | `/gm/character/generate` | `CharacterGenerationController` | Genera un personatge des de prompt i blueprint. |
| `POST` | `/gm/character/generate/batch` | `CharacterGenerationController` | Generació per lots. |
| `POST` | `/gm/character/explain` | `CharacterGenerationController` | Explica un personatge existent. |
| `POST` | `/gm/character/reference` | `CharacterGenerationController` | Desa una referència d’actor. |
| `GET` | `/gm/character/reference/{systemId}/{actorType}` | `CharacterGenerationController` | Recupera una referència. |
| `DELETE` | `/gm/character/reference/{systemId}/{actorType}` | `CharacterGenerationController` | Elimina una referència. |
| `POST` | `/gm/character/validate` | `CharacterGenerationController` | Valida dades de personatge. |
| `POST` | `/adventure/upload` | `AdventureController` | Carrega i ingereix un PDF d’aventura. |
| `GET` | `/adventure/{worldId}` | `AdventureController` | Llista les aventures d’un món. |
| `GET` | `/adventure/{adventureId}/sessions?worldId=…` | `AdventureController` | Llista sessions d’una aventura. |
| `POST` | `/adventure/{adventureId}/start` | `AdventureController` | Crea una sessió d’aventura. |
| `POST` | `/adventure/session/{sessionId}/resume` | `AdventureController` | Reprèn una sessió. |
| `GET` | `/adventure/session/{sessionId}` | `AdventureController` | Recupera l’entitat de sessió. |
| `POST` | `/adventure/session/{sessionId}/process` | `AdventureController` | Processa una petició del director per REST. |
| `POST` | `/adventure/session/{sessionId}/confirm` | `AdventureController` | Processa una confirmació del director per REST. |
| `POST` | `/api/books/upload` | `BookController` | Inicia ingestió asíncrona d’un PDF. |
| `POST` | `/api/books/compendium` | `BookController` | Inicia ingestió d’entrades de compendi. |
| `GET` | `/api/books/{worldId}` | `BookController` | Llista fonts de coneixement d’un món. |
| `GET` | `/api/books/status/{bookId}` | `BookController` | Consulta l’estat d’una font. |
| `DELETE` | `/api/books/{bookId}` | `BookController` | Elimina el registre de la font; no elimina els fragments de l’índex actualment. |
| `POST` | `/api/pdf/upload` | `PdfUploadController` | Endpoint antic de càrrega PDF. |
| `POST` | `/api/system-profile/snapshot` | `SystemProfileController` | Envia una captura de sistema de Foundry. |
| `GET` | `/api/system-profile/{systemId}` | `SystemProfileController` | Recupera un perfil de sistema. |
| `POST` | `/api/system-profile/{systemId}/rebuild` | `SystemProfileController` | Reconstrueix/enriqueix un perfil existent. |
| `GET` | `/audio/{filename}.wav` | `AudioController` | Serveix un clip WAV temporal. |
| `GET` | `/audio/{filename}.mp3` | `AudioController` | Serveix un clip MP3 temporal. |

## STOMP sobre WebSocket

L’endpoint és `/ws`, amb SockJS i una segona entrada WebSocket nativa. El broker habilita `/topic` i `/queue`; les destinations d’aplicació comencen per `/app`.

| Tipus | Destination | Funció |
|---|---|---|
| Enviament | `/app/ping` | Ping; resposta a `/topic/pong`. |
| Enviament | `/app/game-event` | Rep i difon un event a `/topic/game-events`. |
| Enviament | `/app/item/generate` | Genera objectes; resposta a `/queue/item-{id}`. |
| Enviament | `/app/audio/transcribe` | Transcriu un payload d’àudio; resposta a `/queue/{id}`. |
| Enviament | `/app/adventure/transcription` | Envia text transcrit o àudio base64 al director. |
| Enviament | `/app/adventure/confirm` | Respon a una petició de confirmació. |
| Subscripció | `/topic/pong`, `/topic/game-events` | Ping i events globals de Foundry. |
| Subscripció | `/queue/{id}` | Missatges generals per sessió. |
| Subscripció | `/queue/character-{id}`, `/queue/item-{id}`, `/queue/image-{id}`, `/queue/ingestion-{id}` | Progrés de personatges, objectes, imatges i ingestió. |
| Subscripció | `/queue/adventure-{id}` | Events del director per sessió d’aventura. |

Les crides STOMP transporten un `WebSocketMessage` amb `type`, `sessionId`, `payload`, `error` i `timestamp`. Els valors de `MessageType` són `CHARACTER_GENERATION_STARTED`, `CHARACTER_GENERATION_COMPLETED`, `CHARACTER_GENERATION_FAILED`, `IMAGE_GENERATION_STARTED`, `IMAGE_GENERATION_COMPLETED`, `IMAGE_GENERATION_FAILED`, `ITEM_GENERATION_REQUEST`, `ITEM_GENERATION_STARTED`, `ITEM_GENERATION_COMPLETED`, `ITEM_GENERATION_FAILED`, `INGESTION_STARTED`, `INGESTION_PROGRESS`, `INGESTION_COMPLETED`, `INGESTION_FAILED`, `TRANSCRIPTION_COMPLETED`, `TRANSCRIPTION_RECEIVED`, `INTENT_CONFIRMATION_REQUEST`, `INTENT_CONFIRMED`, `INTENT_REJECTED`, `DIRECTOR_NARRATION`, `DIRECTOR_AUDIO_READY`, `NPC_DIALOGUE_AUDIO`, `NPC_AUDIO_READY`, `SCENE_TRANSITION`, `ADVENTURE_STATE_UPDATE`, `NOTIFICATION`, `ERROR`, `PING`, `PONG` i `GAME_EVENT`.

Fonts: [AdventureController](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/controller/AdventureController.java), [BookController](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/controller/BookController.java), [CharacterGenerationController](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/controller/CharacterGenerationController.java), [GameMasterController](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/controller/GameMasterController.java), [WebSocketController](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/controller/WebSocketController.java), [WebSocketConfig](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/config/WebSocketConfig.java), [websocket-client.ts](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/src/websocket-client.ts), [WebSocketMessage](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/dto/WebSocketMessage.java).
