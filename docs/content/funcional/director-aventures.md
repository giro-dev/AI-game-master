---
title: "Director d’aventures"
description: "Carrega una aventura i deixa que el director coordini cada torn."
weight: 70
---

Activa el director de joc a la configuració i obre **Funcions**. Pots pujar un PDF d’aventura, triar una aventura carregada, iniciar una sessió o reprendre’n una de desada. El backend estructura el material en actes, escenes, PNJ i pistes; la sessió conserva escena, pistes descobertes, participants i tensió.

## Torn de joc

Envia text des del panell o activa el micròfon. El servidor classifica la intenció i decideix si cal demanar una tirada. Si cal, torna una acció de tirada a Foundry; quan es publica el resultat, el mòdul el recull i l’envia de nou al director perquè resolgui la situació.

El director combina l’escena i l’estat de l’aventura amb context de regles recuperat per RAG. La resposta pot contenir narració, diàlegs de PNJ, canvis d’estat i accions de Foundry. El mòdul aplica les accions compatibles i rep els canvis en temps real per WebSocket.

Si el classificador demana confirmar una intenció, la interfície presenta **Sí**, **No** i **Reformular**. El servidor reprèn el torn segons la resposta.

La síntesi de veu pot produir narració i veus de PNJ. El servei envia clips i missatges d’estat a la cua WebSocket de la sessió d’aventura.

Fonts: [FeaturesPanel](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/src/ui/features-panel.ts), [AdventureController](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/controller/AdventureController.java), [AdventureDirectorService](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/AdventureDirectorService.java), [prompt del director](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/resources/prompts/adventure_director_system.txt).
