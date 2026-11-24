# Code Context Platform

Self-hosted multi-repository code context service for coding agents. It exposes repository onboarding, signed push indexing, searchable Git generations, semantic retrieval, and six MCP tools over Streamable HTTP.

## Stack

- Java 21, Spring Boot 4.0.10, Spring AI 2.0.2
- PostgreSQL 17 + pgvector, Flyway migrations
- Zoekt for exact and regular-expression search
- Graphify CLI for structural mapping; JavaParser contributes Java method/class chunks
- Ollama with `qwen3-embedding:0.6b` for local embeddings
- JGit for repository sync and source reads

Run `docker compose up --build -d`, then configure Cursor as described in [MCP_SETUP.md](MCP_SETUP.md). Local API key defaults are deliberately limited to loopback in Compose. Production requires explicit keys, webhook secret, GitHub App installation token and database password.

## API quick start

```sh
curl -H 'X-API-Key: local-dev-token' -H 'Content-Type: application/json' \
  -d '{"owner":"fixtures","name":"repo-library","cloneUrl":"file:///fixtures/repo-library","defaultBranch":"main","type":"LIBRARY"}' \
  http://localhost:8080/api/repositories
```

Trigger a first index through `POST /api/repositories/{owner}/{name}/index`. Pushes are accepted at `/webhooks/github/{owner}/{name}` only with a valid GitHub `X-Hub-Signature-256` HMAC. Check job status at `/api/index-jobs/{id}`.

## MCP tools

`search_code`, `read_file`, `find_symbol`, `get_neighbors`, `semantic_search`, and `impact_analysis` are registered as Spring AI tools. MCP and REST requests use `X-API-Key`; every tool applies team/repository access filtering. Source reads resolve from the published Git commit, not the mutable work tree.

## Current implementation limits

The contract extractor suite and deterministic compatibility engine are ported. `impact_analysis` compares the previous successful and current published generations, resolves team-scoped contract consumers against persisted indexes, reports freshness/evidence/completeness, and persists results. It can schedule bounded consumer re-indexing for relevant stale or unindexed repositories. It remains informational (`blocking: false`): method-level behavior analysis is not run, breaking contract consumers are candidates rather than confirmed code failures, and an empty result is not a safety verdict. See [REFERENCE_PORT_MAP.md](REFERENCE_PORT_MAP.md) and [IMPLEMENTATION_REPORT.md](IMPLEMENTATION_REPORT.md) for remaining gaps and validation limits.

The indexing path reuses vectors and extracted records for byte-identical files and uses Zoekt's incremental mode; Graphify still builds the complete graph for a generation. Old unpublished generations and Zoekt generation shards are retained; a retention/garbage collection worker remains to be implemented. Production HA sizing and multi-instance indexing lock tests are also outstanding.
