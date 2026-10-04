---
title: "Frontend"
description: "Mòdul Foundry VTT, inicialització i components d’interfície."
weight: 80
---

El codi TypeScript viu a `master-foundry/src/`; Foundry carrega `dist/main.js` segons `module.json`. El projecte compila TypeScript amb `tsc`, sense una etapa de bundling declarada.

## Inicialització

`main.ts` registra configuració i hooks de Foundry, construeix l’extractor d’esquemes i el generador de blueprints, prepara el client WebSocket i les finestres, connecta el client i envia la captura inicial del sistema. El mòdul registra una finestra separada per generar, xatejar, gestionar biblioteca/configuració i dirigir aventures/transcripció.

## Components

| Àrea | Directoris / fitxers |
|---|---|
| UI | `ui/` conté `GeneratePanel`, `SessionPanel`, `LibraryPanel`, `ConfigPanel` i `FeaturesPanel`, embolcallats per aplicacions Foundry. |
| Esquemes | `schema/` inspecciona actors/items; `blueprints/` genera els blueprints enviats al backend. |
| Estat del sistema | `system-snapshot/` recopila i envia una captura i processa el perfil rebut. |
| Adaptadors | `skills/` manté el registre i les definicions de System Skills. |
| WebSocket | `websocket-client.ts` gestiona SockJS/STOMP, subscripcions, events i requests. |
| Àudio | `services/` captura el micròfon i reprodueix narració i diàlegs. |
| Vista | `templates/` conté Handlebars i `styles/` els estils del mòdul. |

Ordre de compilació configurat: `npm run build` dins `master-foundry` (script `tsc`).

Fonts: [main.ts](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/src/main.ts), [tsconfig.json](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/tsconfig.json), [package.json](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/package.json), [manifest](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/module.json).
