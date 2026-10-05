---
title: "Generació d’objectes"
description: "Genera objectes de joc i importa’ls en un compendi."
weight: 40
---

Des del panell **Generador**, indica què vols crear, selecciona el compendi de tipus *Item* i envia la petició. La petició WebSocket es tramita a `/app/item/generate`; el servidor utilitza el blueprint d’objectes, els tipus vàlids del sistema i, quan hi ha material, exemples/context recuperats de la biblioteca.

Quan arriba la resposta, `main.ts` comprova que el compendi existeix, ajusta els tipus d’objecte no vàlids al primer tipus disponible quan cal i importa els documents generats al compendi amb `pack.importDocument`.

Fonts: [generate-panel.ts](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/src/ui/generate-panel.ts), [WebSocketController](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/controller/WebSocketController.java), [ItemGenerationService](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/ItemGenerationService.java), [main.ts: importació de resultats](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/src/main.ts).
