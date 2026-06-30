CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS appointment;
CREATE SCHEMA IF NOT EXISTS health;
CREATE SCHEMA IF NOT EXISTS mental_health;
CREATE SCHEMA IF NOT EXISTS diagnostic;

-- NEW — enable pgvector extension (must run before any vector column is created)
CREATE EXTENSION IF NOT EXISTS vector;

-- NEW — knowledge_chunks table for RAG retrieval
-- Created here (not via Hibernate ddl-auto) because Hibernate does not
-- know how to generate a `vector(384)` column type natively.
CREATE TABLE IF NOT EXISTS diagnostic.knowledge_chunks (
    id              BIGSERIAL PRIMARY KEY,
    content         TEXT NOT NULL,
    source_title    VARCHAR(255) NOT NULL,
    source_type     VARCHAR(50)  NOT NULL,   -- DISEASE, SYMPTOM, RADIOLOGY_FINDING, etc.
    condition_tag   VARCHAR(100),            -- e.g. "cardiomegaly", "pneumonia" — nullable
    chunk_index     INTEGER NOT NULL,        -- position within source document, for ordering/debugging
    embedding       vector(384) NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

-- IVFFlat index for fast approximate nearest-neighbour search.
-- Without this index, similarity search does a full table scan on every query.
-- "lists = 100" is a reasonable starting point for a knowledge base in the
-- low thousands of chunks; tune upward as the table grows.
CREATE INDEX IF NOT EXISTS knowledge_chunks_embedding_idx
    ON diagnostic.knowledge_chunks
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);