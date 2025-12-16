-- liquibase formatted sql

-- changeset albert:1765832139165-1
CREATE TABLE document_chunks
(
    id              UUID                        NOT NULL,
    content         TEXT                        NOT NULL,
    embedding       VECTOR(768),
    title           VARCHAR(500),
    page            INTEGER,
    chunk_type      VARCHAR(50),
    source_document VARCHAR(255),
    foundry_system  VARCHAR(100),
    metadata        JSONB,
    created_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_document_chunks PRIMARY KEY (id)
);

