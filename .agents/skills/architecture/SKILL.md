---
name: ai-game-master-architecture
description: Architecture guide for the AI Game Master project. Explains the system design, key components, data flow, and domain concepts for AI agents working on the codebase.
---

# AI Game Master - Architecture Skill

## System Overview

The AI Game Master is a two-component system:
1. **master-server** (Spring Boot) — Backend orchestration engine that manages LLM interactions, RAG, audio, and game state
2. **master-foundry** (TypeScript) — Foundry VTT module that provides the UI and captures game state

Communication between them happens via **WebSocket** and **REST API**.

## Data Flow

```
Player Action (Foundry VTT)
  → master-foundry captures input (text or audio)
  → WebSocket to master-server
  → Intent Classifier (determines action type)
  → Roll Decision (determines if dice roll needed)
  → RAG Advisor (fetches relevant rules from OpenSearch)
  → Director Stateful (generates narrative response with game state updates)
  → State Verifier (validates proposed state changes)
  → TTS (converts narration to speech)
  → WebSocket response to master-foundry
  → Foundry VTT renders result (text + audio playback)
```

## Key Service Components

### AI Pipeline Services
- **IntentClassifierService** — Classifies player intent (action, dialogue, meta-game)
- **RollDecisionService** — Determines if a dice roll is required
- **AdventureDirectorService** — Core narrative engine, manages scene flow and tension
- **StateVerifierService** — Validates LLM-proposed game state changes
- **RAGService** — Retrieves relevant game rules from OpenSearch vector store
- **ModelRoutingService** — Selects appropriate AI model per operation

### Character & Content Generation
- **CharacterGenerationService** — AI-powered character creation using blueprints
- **ItemGenerationService** / **ItemGenerationAgent** — Generates game items
- **FieldFillerAgent** — Fills individual fields in character/item schemas
- **ConceptAgent** — Generates character/item concepts

### Audio Services
- **TranscriptionService** / **TranscriptionQueueService** — Whisper STT integration
- **TtsService** — TTS abstraction layer
- **SpeechSynthesisService** — Coordinates TTS generation
- **OpenAiSpeechService** — OpenAI TTS provider
- **PiperVoiceSelector** — Local Piper TTS voice selection
- **AudioStoreService** — Temporary audio file storage

### Knowledge & Content
- **SystemProfileService** / **SystemProfileRepository** — RPG system knowledge profiles
- **AdventureIngestionService** — PDF adventure book ingestion into vector store
- **AdventureSessionService** — Adventure session state management
- **GameMasterManualSolver** — Rule lookup and manual queries

## Domain Model

### Core Entities
- **Adventure** — An adventure module with scenes, NPCs, and clues
- **Scene** — A discrete location/moment in an adventure with read-aloud text and GM notes
- **NPC** — Non-player character with personality, voice, and disposition
- **Clue** — Discoverable information tied to scenes
- **Blueprint** — JSON schema extracted from Foundry VTT actor/item structures
- **SystemProfile** — Learned RPG system knowledge

### AI Model Routing
Each AI operation has configurable routing:
- `preferred-model` — First choice model (e.g., `gpt-4.1-nano` for fast classification)
- `fallback-model` — Used if preferred model fails or is unavailable
- `temperature` — Creativity vs. determinism (0.0 for classification, 0.7 for narration)
- `expected-format` — `json` or `text`
- `max-tokens` — Response length limit
- `latency-budget-ms` — Maximum acceptable latency

## Foundry VTT Module Architecture

### Entry Point
`src/main.ts` registers Foundry VTT hooks and initializes services.

### Services
- **audio-capture-service** — Captures player microphone input
- **audio-playback-service** — Plays TTS audio responses
- **character-generation-service** — UI for AI character generation

### System Integration
- **system-snapshot/** — Captures Foundry VTT game state (actors, items, scenes)
- **schema/** — Extracts actor/item schemas for blueprint generation
- **blueprints/** — Generates blueprints from Foundry system schemas
- **skills/** — Adapters for different RPG system rules
