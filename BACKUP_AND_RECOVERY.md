# Backup and recovery

Back up PostgreSQL with a consistent base backup plus WAL archiving/PITR. Back up Git repository mirrors, Zoekt index shards, and Ollama model volumes independently; PostgreSQL alone is not sufficient to reconstruct exact source or exact search shards. Record the deployed app image, migration version, embedding model digest/tag, vector dimension, and Zoekt image version with each backup set.

Suggested local database dump:

```sh
docker compose exec -T postgres pg_dump -U code_context -Fc code_context > code-context.dump
```

Restore to an isolated PostgreSQL instance, confirm Flyway state and pgvector extension, then mount matching repository mirrors/Zoekt data and verify a published commit's file contents and semantic query before routing agent traffic. If mirrors or search indexes are unavailable, re-onboard and reindex from the configured remote Git source. Never promote a partial rebuild as a published generation.

Production backup retention, restore-time objective, replication strategy, and tested recovery cadence must be set by the operator for the organization's data-retention policy. No measured RPO/RTO is claimed by this implementation.
