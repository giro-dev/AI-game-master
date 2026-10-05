---
title: "Desplegament i desenvolupament"
description: "Serveis de Compose, configuració i ordres locals."
weight: 90
---

## Docker Compose

Spring Boot arrenca automàticament els serveis definits a `master-server/docker-compose.yml` (`spring.docker.compose.enabled: true`, `start-only`).

| Servei | Port publicat | Notes |
|---|---:|---|
| PostgreSQL 17 | `5432` | Base `gamemaster`; dades en volum. |
| OpenSearch 2.19.1 | `9200`, `9600` | Vector store; seguretat desactivada a Compose. |
| OpenSearch Dashboards 2.19.1 | `5601` | Interfície de diagnòstic. |
| faster-whisper-server | `9300 → 8000` | Imatge `latest-cuda`; reserva GPU NVIDIA. |
| Wyoming Piper | `10200` | Configurat amb la veu `ca_ES-upc_ona-medium` a Compose. |

## Configuració i imatge

La configuració principal és `master-server/src/main/resources/application.yml`. Entre les variables d’entorn referenciades hi ha `OPENAI_API_KEY`, `GM-PROJECT` i `GOOGLE_GENAI_API_KEY`. El `Dockerfile` del servidor empaqueta l’aplicació Java en una imatge basada en Temurin.

## Ordres de desenvolupament

```bash
# Backend
cd master-server
./gradlew bootRun
./gradlew test
./gradlew build

# Mòdul Foundry
cd master-foundry
npm run build

# Foundry local en mode desenvolupament
./run-foundry-dev.sh
```

El script `run-foundry-dev.sh` conté rutes locals absolutes específiques de l’entorn on es va crear; adapta-les abans d’utilitzar-lo en una altra màquina. Hi ha proves a `master-server/src/test/`, incloses les de sessions, generació i validació d’estat; la documentació no ha canviat el codi d’aplicació.

## Web de documentació

Amb Hugo instal·lat:

```bash
cd docs
hugo server
```

Per compilar la sortida de publicació:

```bash
hugo --gc --minify --baseURL https://giro-dev.github.io/AI-game-master/
```

Fonts: [docker-compose.yml](https://github.com/giro-dev/AI-game-master/blob/main/master-server/docker-compose.yml), [application.yml](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/resources/application.yml), [Dockerfile](https://github.com/giro-dev/AI-game-master/blob/main/master-server/Dockerfile), [AdventureSessionServiceTest.java](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/test/java/dev/agiro/masterserver/service/AdventureSessionServiceTest.java), [CharacterCreationFlowTest.java](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/test/java/dev/agiro/masterserver/service/CharacterCreationFlowTest.java), [StateVerifierServiceTest.java](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/test/java/dev/agiro/masterserver/service/StateVerifierServiceTest.java), [run-foundry-dev.sh](https://github.com/giro-dev/AI-game-master/blob/main/run-foundry-dev.sh), [package.json](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/package.json).
