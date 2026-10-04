---
title: "Instal·lació"
description: "Com posar en marxa el servidor i connectar-hi el mòdul de Foundry."
weight: 20
---

## Servidor

El backend és a `master-server/`. Des d’aquest directori, inicia Spring Boot:

```bash
cd master-server
./gradlew bootRun
```

La configuració de Spring activa Docker Compose en arrencar l’aplicació (`spring.docker.compose.enabled: true`) i utilitza `docker-compose.yml` per iniciar PostgreSQL, OpenSearch, Whisper i Piper. Les imatges de veu es poden descarregar en el primer ús.

La configuració llegeix aquestes variables d’entorn: `OPENAI_API_KEY` (clau d’OpenAI), `GM-PROJECT` (projecte associat a embeddings d’OpenAI) i `GOOGLE_GENAI_API_KEY` (configuració de Gemini). Consulta [Desplegament i desenvolupament](../../tecnica/desplegament-desenvolupament/) per a serveis, ports i ordres de desenvolupament.

## Mòdul de Foundry

Instal·la el directori del mòdul `master-foundry` a Foundry VTT, o bé crea un enllaç des del directori de mòduls a aquest directori. El manifest declara l’identificador `ai-gm` i compatibilitat mínima amb Foundry 11 i verificada fins a la 14.

Un cop activat el mòdul, obre la configuració del mòdul **AI Game Master** i defineix l’URL del servidor. El valor inicial és `http://localhost:8080`; si el servidor és en un altre host, substitueix-lo per la seva URL accessible des de Foundry.

## Requisit de GPU

El servei Whisper del `docker-compose.yml` fa servir la imatge `faster-whisper-server:latest-cuda` i reserva un dispositiu NVIDIA. Per tant, aquesta configuració de transcripció depèn d’una GPU NVIDIA amb suport CUDA. La resta del stack no declara aquesta reserva de GPU.

Fonts: [application.yml](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/resources/application.yml), [docker-compose.yml](https://github.com/giro-dev/AI-game-master/blob/main/master-server/docker-compose.yml), [manifest `module.json`](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/module.json), [settings.ts](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/src/settings.ts).
