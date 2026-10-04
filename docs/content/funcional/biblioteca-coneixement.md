---
title: "Biblioteca de coneixement"
description: "Ingereix llibres i compendis perquè l’assistent pugui recuperar-ne informació."
weight: 50
---

La **Biblioteca** accepta PDFs i compendis de Foundry. En tots dos casos el panell envia el context del sistema de joc i del món; en el cas d’un PDF també es pot indicar el títol. La ingestió del llibre és asíncrona i el panell mostra l’estat i el progrés per WebSocket.

Des de la llista pots consultar les fonts d’un món i esborrar-ne el registre. **La implementació actual no elimina els fragments del vector store en esborrar una font**; el codi ho deixa indicat com una tasca pendent. Els compendis es serialitzen a partir de les entrades disponibles abans de trametre’ls.

## Com aprofita el contingut la IA?

Els documents es tallen en fragments, classifiquen per tipus i indexen a OpenSearch. Els agents poden recuperar regles, creació de personatges, objectes, conjurs, bestiari i altres continguts segons el flux. La ingestió també pot extreure entitats estructurades per fer-les cercables.

## Perfil del sistema i habilitats

El mòdul envia una captura (*snapshot*) de l’esquema del sistema Foundry i el servidor manté un perfil de sistema que s’enriqueix a partir del material ingerit. Des de **Configuració** es pot tornar a aprendre el sistema o gestionar el perfil.

Les **System Skills** són adaptadors JSON opcionals, vinculats a un `systemId` i, si cal, a un `worldId`. Poden afegir o modificar camps d’actors, restriccions, instruccions de creació, ítems per defecte i àlies de camps. Són útils quan la introspecció automàtica no exposa prou esquema; es poden importar, crear i activar des de la pestanya Sistema.

Fonts: [LibraryPanel](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/src/ui/library-panel.ts), [BookController](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/controller/BookController.java), [IngestionPipeline](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/pdf_extractor/IngestionPipeline.java), [SystemProfileService](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/SystemProfileService.java), [guia de System Skills](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/examples/skills/README.md).
