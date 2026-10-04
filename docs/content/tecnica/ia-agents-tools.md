---
title: "IA, agents i eines"
description: "Crides LLM, prompts, eines disponibles i paràmetres de routing."
weight: 40
---

## Crides LLM i prompts

| Classe | Operació de routing | Prompt |
|---|---|---|
| `IntentClassifierService` | `intent-classifier` | Instruccions al codi. |
| `RollDecisionService` | `roll-decision` | `roll_decision_system.txt`. |
| `AdventureDirectorService` | `director-stateful` | `adventure_director_system.txt`. |
| `StateVerifierService` | `state-verifier` | `state_verifier_system.txt`. |
| `GameMasterService` | `manual-answer` | `session_assistant_system.txt`. |
| `GameMasterManualSolver` | `manual-answer` | `manual_system.txt`. |
| `ConceptAgent` | `concept-agent` | Instruccions al codi. |
| `FieldFillerAgent` | `field-filler` | Instruccions al codi. |
| `ItemGenerationAgent` | `item-generator` | Instruccions al codi. |
| `ItemGenerationService` | `item-generator` | `item_generation_system.txt`. |
| `EntityExtractor` | `entity-extractor` | `entity_extractor_system.txt`. |
| `AdventureIngestionService` | `adventure-ingestion` | Prompt estructural al codi. |
| `DocumentClassifier` | Configuració pròpia (`gpt-4.1-mini`) | `chunk_classifier_system.txt`. |
| `CharacterGenerationService` | Configuració pròpia (`gpt-4o-mini`) per explicar personatges | Prompt d’explicació al codi. |
| `SystemProfileService` | Configuració pròpia (`gpt-4.1-mini`) | Prompts d’anàlisi/agrupació al codi. |

`CharacterGenerationService` coordina els agents especialitzats per a la creació, mentre que la seva crida pròpia s’utilitza per explicar un personatge. El codi també conté altres crides LLM per construir perfils de sistema.

## Eines `@Tool`

| Classe / eina | Funció | Crides que la reben |
|---|---|---|
| `RAGService.searchItemContext` | Cerca context d’objectes per sistema i tipus. | `ItemGenerationAgent`; també és disponible a `ConceptAgent` i `FieldFillerAgent`. |
| `RAGService.searchCharacterCreationContext` | Cerca instruccions de creació de personatges. | `ConceptAgent` i `FieldFillerAgent`. |
| `RAGService.searchExtractedEntities` | Cerca entitats estructurades filtrades per sistema, món i tipus. | `ConceptAgent`, `FieldFillerAgent` i `ItemGenerationAgent`. |
| `RAGService.searchBestiaryContext` | Cerca bestiari, blocs d’estadístiques i entitats PNJ. | `ConceptAgent`, `FieldFillerAgent` i `ItemGenerationAgent`. |
| `GameMasterManualSolver.solveDoubt` | Respon preguntes de regles amb manuals pujats. | `ConceptAgent` i `FieldFillerAgent`. |
| `RollDecisionService.decide` | Determina si cal una tirada i descriu el tipus/dificultat. | `AdventureDirectorService`. |
| `AdventureStateTool.getCurrentScene` | Consulta l’escena actual. | `AdventureDirectorService`. |
| `AdventureStateTool.getDiscoveredClues` | Consulta les pistes descobertes. | `AdventureDirectorService`. |
| `AdventureStateTool.getNpcDispositions` | Consulta disposicions dels PNJ coneguts. | `AdventureDirectorService`. |
| `AdventureStateTool.getTensionLevel` | Consulta la tensió actual. | `AdventureDirectorService`. |

Les crides del director també afegeixen un `QuestionAnswerAdvisor` per al RAG i memòria de conversa. L’eina d’estat és de lectura: les mutacions proposades tornen a la resposta estructurada del director.

## Routing configurat

Els valors següents provenen de `game-master.routing.operations` a `application.yml`. Les columnes `tokens` i `latència` són els límits esperats configurats; `ModelRoutingService` selecciona el model preferit configurat (o el global per defecte si no n’hi ha), no implementa un canvi automàtic al model fallback.

| Operació | Preferit → fallback | Temp. | Tokens | Latència (ms) |
|---|---|---:|---:|---:|
| `intent-classifier` | `gpt-4.1-nano` → `gpt-4.1-mini` | 0.0 | 256 | 2.000 |
| `roll-decision` | `gpt-4.1-nano` → `gpt-4.1-mini` | 0.0 | 512 | 3.000 |
| `manual-answer` | `gpt-4.1-mini` → `gpt-4.1` | 0.3 | 1.024 | 8.000 |
| `director-stateful` | `gpt-4.1-mini` → `gpt-4.1` | 0.7 | 2.048 | 15.000 |
| `state-verifier` | `gpt-4.1-mini` → `gpt-4.1` | 0.0 | 512 | 5.000 |
| `concept-agent` | `gpt-4.1-mini` → `gpt-4.1` | 0.8 | 1.024 | 10.000 |
| `field-filler` | `gpt-4.1-mini` → `gpt-4.1` | 0.8 | 2.048 | 15.000 |
| `item-generator` | `gpt-4.1-mini` → `gpt-4.1` | 0.8 | 2.048 | 15.000 |
| `entity-extractor` | `gpt-4.1-nano` → `gpt-4.1-mini` | 0.1 | 1.024 | 8.000 |
| `adventure-ingestion` | `gpt-4.1-mini` → `gpt-4.1` | 0.3 | 4.096 | 60.000 |

Consulta també [Punts de millora](../../millores/) per a l’anàlisi crítica del routing i l’ús de les eines.

Fonts: [application.yml](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/resources/application.yml), [ModelRoutingService](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/ModelRoutingService.java), [AdventureDirectorService](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/AdventureDirectorService.java), [ConceptAgent](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/ConceptAgent.java), [FieldFillerAgent](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/FieldFillerAgent.java), [ItemGenerationAgent](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/ItemGenerationAgent.java), [IntentClassifierService](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/IntentClassifierService.java), [chunk_classifier_system.txt](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/resources/prompts/chunk_classifier_system.txt), [adventure_director_system.txt](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/resources/prompts/adventure_director_system.txt).
