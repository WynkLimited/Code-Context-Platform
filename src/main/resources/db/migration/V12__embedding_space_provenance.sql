-- Legacy vectors remain available to old readers but are never reused by a new embedding space.
ALTER TABLE index_generations
    ADD COLUMN embedding_fingerprint text CHECK (embedding_fingerprint IS NULL OR embedding_fingerprint ~ '^[0-9a-f]{64}$'),
    ADD COLUMN embedding_metadata jsonb NOT NULL DEFAULT '{}'::jsonb;
