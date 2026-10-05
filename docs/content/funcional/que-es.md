---
title: "Què és AI Game Master?"
description: "Un resum de les funcions i de qui les pot utilitzar."
weight: 10
---

AI Game Master connecta Foundry VTT amb un servidor Spring Boot que coordina agents d’IA, cerca en material de joc i serveis de veu. La interfície del mòdul es divideix en finestres per a generació, xat, biblioteca, configuració, direcció d’aventures i transcripció.

## Qui el fa servir?

El **director de joc (GM)** configura el servidor i l’accés, i pot utilitzar totes les funcions. Els rols de Foundry —jugador, jugador de confiança i assistent de GM— poden rebre accés per funció; de manera predeterminada, les funcions estan restringides al GM. La configuració del mòdul garanteix que el GM sempre manté l’accés.

## Funcions principals

| Funció | Què permet fer |
|---|---|
| Generador | Crear actors amb l’esquema detectat del sistema de joc i generar objectes. |
| Xat / sessió | Preguntar a l’assistent sobre la partida amb context opcional del token i del món. |
| Biblioteca | Ingerir PDFs i compendis perquè el sistema els pugui consultar. |
| Director de joc | Carregar aventures, reprendre sessions i coordinar narració, tirades i estat. |
| Transcripció | Enviar àudio capturat pel micròfon a Whisper durant una aventura. |

Les funcions del mòdul, incloses la matriu de rols i les opcions de configuració, es detallen a [Configuració](../configuracio/).

Fonts: [registre de finestres i permisos a `main.ts`](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/src/main.ts), [settings.ts](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/src/settings.ts), [manifest del mòdul](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/module.json).
