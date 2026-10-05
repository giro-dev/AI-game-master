---
title: "Configuració"
description: "Opcions del mòdul i matrius d’accés per rol."
weight: 90
---

## Opcions del mòdul

| Clau | Tipus i valor inicial | Ús |
|---|---|---|
| `serverUrl` | Cadena; `http://localhost:8080` | URL del backend. |
| `enableTranscription` | Booleà; `false` | Activa la captura i transcripció de veu a les sessions. |
| `enableGameDirector` | Booleà; `false` | Activa el director d’aventures. |
| `rolesGenerator` | Cadena JSON; `[4]` | Rols autoritzats al generador. |
| `rolesChat` | Cadena JSON; `[4]` | Rols autoritzats al xat/sessió. |
| `rolesLibrary` | Cadena JSON; `[4]` | Rols autoritzats a la biblioteca. |
| `rolesGameDirector` | Cadena JSON; `[4]` | Rols autoritzats al director. |
| `rolesTranscription` | Cadena JSON; `[4]` | Rols autoritzats a la transcripció. |
| `ragChunkTypesByRole` | Cadena JSON; mapa per rol | Tipus de fragments RAG consultables per cada rol. |
| `defaultItemPack` | Cadena; buida | Clau antiga conservada per compatibilitat. |

Les opcions de rol i RAG són configuracions de món; no es mostren com a opcions generals de Foundry. La interfície de configuració permet gestionar-les i el GM no es pot excloure.

## Accés a les funcions

Els rols són `1` Jugador, `2` Jugador de confiança, `3` Assistent de GM i `4` GM.

| Funció | Jugador | De confiança | Assistent | GM per defecte |
|---|:---:|:---:|:---:|:---:|
| Generador | — | — | — | ✓ |
| Xat / sessió | — | — | — | ✓ |
| Biblioteca | — | — | — | ✓ |
| Director de joc | — | — | — | ✓ |
| Transcripció | — | — | — | ✓ |

El GM sempre té accés; les cel·les dels altres rols es poden activar a la configuració.

## Accés als fragments RAG per defecte

| Tipus de fragment | Jugador | De confiança | Assistent | GM |
|---|:---:|:---:|:---:|:---:|
| `rules` | ✓ | ✓ | ✓ | ✓ |
| `character_creation` | — | — | ✓ | ✓ |
| `item_definition` | — | — | ✓ | ✓ |
| `spell_definition` | — | — | ✓ | ✓ |
| `npc_stat_block` | — | — | ✓ | ✓ |
| `bestiary` | ✓ | ✓ | ✓ | ✓ |
| `example` | — | ✓ | ✓ | ✓ |
| `lore` | ✓ | ✓ | ✓ | ✓ |
| `table` | — | ✓ | ✓ | ✓ |
| `other` | — | — | ✓ | ✓ |

Fonts: [settings.ts](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/src/settings.ts), [ConfigPanel](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/src/ui/config-panel.ts).
