---
title: "Àudio"
description: "Serveis de reconeixement, síntesi i servei dels clips."
weight: 70
---

## STT

El mòdul captura àudio amb `MediaRecorder`, el codifica en base64 i l’envia a `/app/adventure/transcription`. El backend descodifica l’àudio, invoca `TranscriptionService` i la interfície del servei Whisper compatible amb OpenAI a `/v1/audio/transcriptions`. La configuració declara `whisper.url`, `whisper.model` i `whisper.language` (per defecte `http://localhost:9300`, `large-v3`, `ca`).

## TTS

`TtsService` selecciona Piper o OpenAI segons `tts.provider`. Piper utilitza protocol Wyoming sobre TCP i la configuració `piper-tts.host`, `piper-tts.port` i veus disponibles; `OpenAiSpeechService` implementa la via d’OpenAI. `SpeechSynthesisService` coordina la síntesi de narració i diàlegs de PNJ.

`PiperVoiceSelector` resol àlies i tria veu per narrador/PNJ. Els clips temporals s’escriuen a `audio.store.dir` i `AudioController` publica fitxers WAV/MP3 sota `/audio/`. La configuració de retenció és `audio.store.ttl-minutes` (60 per defecte).

Fonts: [TranscriptionService](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/TranscriptionService.java), [TtsService](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/TtsService.java), [SpeechSynthesisService](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/SpeechSynthesisService.java), [OpenAiSpeechService](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/OpenAiSpeechService.java), [AudioStoreService](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/AudioStoreService.java), [application.yml](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/resources/application.yml).
