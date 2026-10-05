---
title: "Backend"
description: "Mapa de paquets i classes del servidor Spring Boot."
weight: 20
---

El backend Java viu sota `dev.agiro.masterserver`. Els controladors exposen HTTP i STOMP; els serveis coordinen lògica de domini, models i persistència; les entitats i repositoris defineixen l’estat persistent.

## Paquets

| Paquet | Responsabilitat |
|---|---|
| `config` | Configuració de Spring, routing de models, TTS i WebSocket. |
| `controller` | Controladors REST, àudio i missatges STOMP. |
| `dto` | Peticions, respostes, events de progrés i sobre WebSocket. |
| `entity` / `repository` | Persistència de perfils, referències i aventures. |
| `model` | Model d’aventura i sessió. |
| `service` | Agents, RAG, direcció, àudio, generació i perfils. |
| `pdf_extractor` | Lectura i ingestió de PDFs i documents. |
| `util` | Utilitats compartides, com el sanejament de JSON. |

## Controladors

| Classe | Funció |
|---|---|
| `AdventureController` | Càrrega d’aventures i gestió de sessions. |
| `AudioController` | Serveix fitxers WAV i MP3 generats. |
| `BookController` | Ingestió, consulta i eliminació de llibres/compendis. |
| `CharacterGenerationController` | Generació, explicació i referències de personatge. |
| `GameMasterController` | Endpoint de resposta del xat de Game Master. |
| `GameMasterManualController` | Consulta de regles mitjançant `/gm/resolve`. |
| `PdfUploadController` | Endpoint de càrrega PDF de la via antiga. |
| `SystemProfileController` | Captures, consulta i reconstrucció de perfils. |
| `WebSocketController` | Destinations d’aplicació i emissió de missatges STOMP. |

## Serveis principals

| Classe | Funció |
|---|---|
| `AdventureDirectorService` | Classifica torns, decideix tirades i genera narració/estat. |
| `AdventureIngestionService` | Converteix un PDF d’aventura en mòdul estructurat. |
| `AdventureSessionService` | Crea sessions i actualitza els seus camps derivats. |
| `AdventureStateTool` | Eines de lectura de l’estat de la sessió per al director. |
| `AudioStoreService` | Desa temporalment clips d’àudio. |
| `CharacterGenerationService` | Orquestra concepte, camps i objectes de personatge. |
| `ConceptAgent` / `FieldFillerAgent` | Generen el concepte i omplen camps del blueprint. |
| `ItemGenerationAgent` / `ItemGenerationService` | Generen objectes de personatge o d’un compendi. |
| `GameMasterService` / `GameMasterManualSolver` | Responen peticions de xat i consultes de manuals. |
| `IntentClassifierService` / `RollDecisionService` | Classifiquen intencions i decideixen tirades. |
| `ModelRoutingService` | Construeix opcions de model i registra latència per operació. |
| `OpenAiSpeechService` / `TtsService` | Implementació OpenAI i abstracció del proveïdor TTS. |
| `PiperVoiceSelector` / `SpeechSynthesisService` | Selecció de veu i síntesi de narració/diàlegs. |
| `RAGService` | Cerca filtrada de fragments i entitats. |
| `StateVerifierService` | Verifica mutacions crítiques quan està habilitat. |
| `SystemAwarePromptBuilder` | Construeix context de prompt conscient del sistema. |
| `SystemProfileService` | Gestiona perfils i personatges de referència. |
| `TranscriptionService` / `TranscriptionQueueService` | Crida Whisper i utilitats de transcripció. |

## Resta de classes

| Paquet | Classes i funció |
|---|---|
| `config` | `GameMasterConfig` — propietats del GM; `ModelRoutingProperties` — paràmetres de routing; `OpenAiTtsConfig` — opcions de veu OpenAI; `PiperTtsConfig` — opcions i veus de Piper; `WebSocketConfig` — broker i endpoints STOMP. |
| `pdf_extractor` | `PDFDocumentReader` — extreu documents de PDF; `TableToMarkdownDocumentTransformer` — transforma taules detectades; `DocumentClassifier` — classifica fragments; `EntityExtractor` — extreu entitats estructurades; `IngestionPipeline` — coordina ingestió de llibres; `GameMasterMetadataEnricher` — afegeix metadades al pipeline antic; `PdfProcessingService` — servei de processament de la via antiga; `ImageData` — dades d’imatge extretes; `PDFNotProcessable` — excepció de PDF no processable; `Section` — tipus de secció. |
| `model` | `AdventureModule` — aventura amb actes, PNJ i pistes; `AdventureSession` — estat d’una partida; `Act` — agrupació ordenada d’escenes; `Scene` — escena amb text, notes i sortides; `SceneTransition` — transició encastada entre escenes; `NpcProfile` — dades i veu d’un PNJ; `Clue` — pista i condicions de descoberta. |
| `entity` / `repository` | `SystemProfileEntity` i `ReferenceCharacterEntity` — registres JPA; repositoris de perfil i referència (`SystemProfileJpaRepository`, `SystemProfileRepository`, `ReferenceCharacterJpaRepository`, `ReferenceCharacterRepository`) i d’aventura/sessió (`AdventureModuleRepository`, `AdventureSessionRepository`). |
| `dto` | `AbilityDto` — habilitat; `AdventureSceneDto` — escena transferible; `AdventureSessionListItemDto` / `AdventureSessionStateDto` — llista i estat de sessió; `BatchCharacterRequest` / `BatchCharacterResponse` — generació per lots; `BookDto` / `BookUploadRequest` — font de coneixement; `CharacterBlueprintDto` — esquema; `CharacterCreationEvent` — progrés de personatge; `CompendiumIngestionRequest` — compendi; `CreateCharacterRequest` / `CreateCharacterResponse` — generació; `DirectorRequest` / `DirectorResponse` — torn del director; `ExplainCharacterRequest` / `ExplainCharacterResponse` — explicació; `GameMasterRequest` / `GameMasterResponse` — xat; `ImageGenerationEvent` — progrés d’imatge; `IngestionEvent` — progrés d’ingesta; `IntentClassification` — intenció classificada; `ItemGenerationEvent` / `ItemGenerationRequest` / `ItemGenerationResponse` — generació d’objectes; `NpcDialogueDto` — diàleg; `ReferenceCharacterDto` — actor de referència; `RollDecision` — decisió de tirada; `StateUpdateDto` — mutacions d’aventura; `SystemProfileDto` / `SystemSnapshotDto` — perfil i captura; `ValidateCharacterRequest` / `ValidateCharacterResponse` — validació; `WebSocketMessage` — embolcall de missatge; `WorldStateDto` — context del món. `BookUploadRequest`, `MetadataField`, `PromptRequest`, `RagContextRequest` i `RagSearchResponse` també són tipus declarats al paquet. |
| `util` | `JsonUtils` — neteja de fences i utilitats de JSON. |

Fonts: [MasterServerApplication.java](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/MasterServerApplication.java), [AdventureDirectorService.java](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/AdventureDirectorService.java), [CharacterGenerationController.java](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/controller/CharacterGenerationController.java), [AdventureModule.java](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/model/AdventureModule.java), [SystemProfileEntity.java](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/entity/SystemProfileEntity.java), [WebSocketMessage.java](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/dto/WebSocketMessage.java).
