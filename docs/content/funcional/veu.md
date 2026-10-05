---
title: "Veu"
description: "Transcripció del micròfon i síntesi de narració i diàlegs."
weight: 80
---

## Transcripció de veu (STT)

Amb la transcripció activada a la configuració, el botó del micròfon del director engega o atura la captura. El mòdul envia l’àudio codificat al canal WebSocket de transcripció de l’aventura; si rep àudio, el servidor crida el servei compatible amb Whisper. La configuració per defecte indica idioma `ca` i model `large-v3`.

El servei Whisper de Compose fa servir l’etiqueta `latest-cuda` i demana GPU NVIDIA; consulta [Instal·lació](../instal-lacio/) abans d’activar-lo en un host sense GPU.

## Síntesi de veu (TTS)

La configuració de servidor permet triar `piper` o `openai` com a proveïdor TTS. Piper es comunica amb Wyoming i la selecció de veu pot tenir en compte veu, gènere i idioma del PNJ; OpenAI utilitza el servei de veu configurat. El mòdul reprodueix les URL d’àudio rebudes o, com a alternativa, àudio en base64.

El servidor publica fitxers temporals a `/audio/{nom}.wav` o `/audio/{nom}.mp3`. El directori és `${java.io.tmpdir}/ai-gm-audio` per defecte i el TTL de neteja configurat és de 60 minuts.

Fonts: [AudioCaptureService](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/src/services/audio-capture-service.ts), [TranscriptionService](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/TranscriptionService.java), [TtsService](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/TtsService.java), [PiperVoiceSelector](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/PiperVoiceSelector.java), [AudioController](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/controller/AudioController.java), [application.yml](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/resources/application.yml).
