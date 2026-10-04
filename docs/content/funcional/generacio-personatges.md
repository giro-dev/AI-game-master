---
title: "Generació de personatges"
description: "Crea un actor amb l’esquema del sistema de joc actiu."
weight: 30
---

El panell **Generador** permet triar un tipus d’actor, seleccionar els camps que es volen omplir i descriure el personatge. El mòdul extreu l’esquema de Foundry i el transforma en un *blueprint* amb els camps i les restriccions del sistema actiu.

## Flux

1. Tria el tipus d’actor i els camps a generar; pots inspeccionar el blueprint abans d’enviar-lo.
2. Escriu el concepte del personatge. El panell adjunta el blueprint i, si n’hi ha, un personatge de referència del mateix sistema i tipus d’actor. També pot construir una referència implícita a partir dels actors disponibles.
3. El servidor genera el concepte, omple els camps i proposa objectes d’equipament en passos separats.
4. El panell mostra el progrés rebut per WebSocket i valida les dades contra el blueprint.
5. Revisa el resultat i crea l’actor a Foundry o exporta’n el JSON.

El GM pot seleccionar un actor existent com a referència des del menú contextual d’un actor. Una referència guardada s’utilitza com a plantilla estructural, no com a actor nou.

## Explicació i lots

El mòdul també pot demanar una explicació narrativa d’un personatge existent. El controlador exposa una operació de generació per lots (`/gm/character/generate/batch`): rep fins a 10 personatges, els genera seqüencialment amb variacions del prompt i retorna els resultats i errors.

Fonts: [generate-panel.ts](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/src/ui/generate-panel.ts), [servei frontend de generació](https://github.com/giro-dev/AI-game-master/blob/main/master-foundry/src/services/character-generation-service.ts), [CharacterGenerationController](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/controller/CharacterGenerationController.java), [CharacterGenerationService](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/CharacterGenerationService.java).
