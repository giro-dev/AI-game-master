---
title: "Ingesta RAG"
description: "Etapes d’ingesta, metadades, tipus de fragments i cerca."
weight: 50
---

## Ingesta de llibres PDF

`IngestionPipeline` s’executa en segon pla i publica progrés per WebSocket:

1. Llegeix el PDF i el divideix en fragments de text; aplica `TableToMarkdownDocumentTransformer` a les taules detectades.
2. Afegeix les metadades base (llibre, món, sistema i fitxer).
3. Classifica fragments amb el model en lots.
4. Extreu entitats estructurades dels tipus de fragment adients.
5. Desa fragments i entitats al `VectorStore`, configurat amb OpenSearch.

Els fragments es construeixen amb `TokenTextSplitter` (mida 2.000, mínim de 300 caràcters i longitud mínima per inserir de 50). Els errors de classificació deixen el tipus com `other`.

## Metadades i tipus

Metadades emprades al codi: `book_id`, `world_id`, `foundry_system`, `game_system` (compatibilitat), `book_title`, `file_name`, `chunk_type`, `section_title`, `entity_names`, `document_type`, `entity_type`, `entity_name` i `source_chunk_id`. El transformador de taules afegeix `contains_table`, `table_count` i `table_format`. Les entrades de compendi també conserven `source_type`, `pack_id`, `pack_label`, `pack_document_type`, `entry_id`, `entry_name` i `entry_type`.

Els tipus de fragments són `rules`, `character_creation`, `item_definition`, `spell_definition`, `npc_stat_block`, `bestiary`, `example`, `lore`, `table` i `other`. Les entitats extretes s’indexen amb `document_type=extracted_entity`.

## Índex i altres ingestes

La configuració del vector store declara índex `vector-store`, embeddings de 1.536 dimensions i similitud cosinus. La ingestió d’aventures segueix una via separada: `AdventureIngestionService` genera l’estructura de mòdul amb actes, escenes, PNJ i pistes. Els compendis de Foundry s’envien com a entrades serialitzades i passen pel pipeline d’ingesta de compendis.

Fonts: [IngestionPipeline](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/pdf_extractor/IngestionPipeline.java), [DocumentClassifier](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/pdf_extractor/DocumentClassifier.java), [EntityExtractor](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/pdf_extractor/EntityExtractor.java), [AdventureIngestionService](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/java/dev/agiro/masterserver/service/AdventureIngestionService.java), [application.yml](https://github.com/giro-dev/AI-game-master/blob/main/master-server/src/main/resources/application.yml).
