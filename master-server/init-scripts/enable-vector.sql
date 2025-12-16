-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Note: The document_chunks table will be created by Hibernate (JPA ddl-auto: update)
-- After the table is created, you may want to add an HNSW index for faster similarity search:
CREATE INDEX IF NOT EXISTS document_chunks_embedding_idx ON document_chunks USING hnsw (embedding vector_cosine_ops);
