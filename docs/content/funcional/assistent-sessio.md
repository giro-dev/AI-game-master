---
title: "Assistent de sessió"
description: "Xat amb context de la partida i preguntes sobre les regles."
weight: 60
---

La finestra **Xat** envia el missatge a `POST /gm/respond`. Pots triar si s’inclou un token seleccionat i el context del món; el client també envia el sistema de Foundry i els tipus de fragments RAG permesos per al rol actual.

Si no hi ha cap token seleccionat, el servidor deriva la pregunta al solucionador de manuals, que consulta el coneixement ingerit. Amb un token seleccionat, la petició passa pel servei principal de Game Master i pot tornar narració i accions que el mòdul aplica a Foundry.

Fonts: [SessionPanel](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/src/ui/session-panel.ts), [GameMasterController](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/controller/GameMasterController.java), [GameMasterService](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/GameMasterService.java), [GameMasterManualSolver](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/GameMasterManualSolver.java).
