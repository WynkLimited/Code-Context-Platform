# Database design

Flyway `V1__initial_schema.sql` enables `vector` and `pgcrypto`, then creates:

- `repositories`, `team_repository_access`: registry, ownership labels and team authorization.
- `index_jobs`: unique delivery idempotency, state, attempt count, error and availability timestamp.
- `index_generations`, `generation_files`: per-repository immutable commit generations and file blob hashes.
- `code_nodes`, `code_edges`: Graphify/Java structural nodes and evidence-bearing relations.
- `contracts`, `contract_links`: exports/imports with file, line, source text, confidence, dependency version, metadata, and generation-paired relations.
- `contract_index_metadata`: per-generation schema, extractor, compatibility/generator versions, hash, and surface counts.
- `cross_repo_impact_analyses`: latest persisted team-scoped analysis by origin generation, including status, commit pair, and JSON result; previous index generations and impact rows are retained.
- `code_chunks`: code-aware text, symbol/line bounds, SHA-256 hash and pgvector embedding. HNSW uses cosine distance.

The pgvector dimension is a Flyway placeholder (`EMBEDDING_DIMENSIONS`, default 1,024). The application validates model output dimension before insert/query. Do not change the value after initializing a database without a planned migration and re-embedding process.

Published indexes are selected with the `index_generations.published` flag. A publication transaction switches the current generation after all indexing stages report success. Old generations are retained for comparison and rollback; an automated pruning policy is not implemented. `contract_links` join contracts from provider and consumer generations; consumers must join both generation rows to current publication state before displaying a relationship. Repository disable removes active grants and links while retaining generation and contract rows.
