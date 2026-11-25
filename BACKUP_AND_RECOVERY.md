# Backup and recovery

Back up PostgreSQL with a consistent base backup plus WAL archiving/PITR. Back up Git repository mirrors, Zoekt index shards, and Ollama model volumes independently; PostgreSQL alone is not sufficient to reconstruct exact source or exact search shards. Record the deployed app image, migration version, embedding model digest/tag, vector dimension, and Zoekt image version with each backup set.

Suggested local database dump:

```sh
docker compose exec -T postgres pg_dump -U code_context_app -Fc code_context > code-context.dump
```

Restore to an isolated PostgreSQL instance, confirm Flyway state and pgvector extension, then mount matching repository mirrors/Zoekt data and verify a published commit's file contents and semantic query before routing agent traffic. If mirrors or search indexes are unavailable, re-onboard and reindex from the configured remote Git source. Never promote a partial rebuild as a published generation.

Production backup retention, restore-time objective, replication strategy, and tested recovery cadence must be set by the operator for the organization's data-retention policy. No measured RPO/RTO is claimed by this implementation.

## Reproducible logical backups and restores

`scripts/backup-postgres.sh` accepts `DATABASE_URL` (plain PostgreSQL URI or JDBC URI prefix), `DATABASE_USER`, `DATABASE_PASSWORD` and `BACKUP_DIR`. It creates a unique private directory with a custom-format archive, SHA-256 sidecar and JSON metadata. Preserve all three together in access-controlled backup storage. A failed dump is removed. Credentials are passed via environment and are not printed. `DRY_RUN=1` performs no database access and provides no restore evidence.

For an explicitly isolated restore database, set `RESTORE_URL`, `RESTORE_USER`, `RESTORE_PASSWORD` and `DUMP_FILE`, then run `bash scripts/restore-smoke.sh`. The script replaces target database objects using an atomic `pg_restore --single-transaction --exit-on-error`; verify the target URI before execution. It requires the archive's `.sha256` sidecar or a trusted `EXPECTED_SHA256`, verifies integrity before database changes, and fails if restored application tables, pgvector or successful Flyway history are absent. Older archives need their independently verified checksum supplied via `EXPECTED_SHA256`; there is no checksum bypass. The optional `MCP_URL`/`TEAM_API_KEY` check is application health only, not a tool invocation test.

Mandatory CI step **Verify PostgreSQL logical backup and restore** runs the actual scripts inside the disposable PostgreSQL 17/pgvector service using matching-version client tools. It requires populated repository, semantic edge and saved impact data, fingerprints every public table and an additional JSON/vector witness, backs up, changes the witness in the source, restores into a separate test database and compares all row counts and content fingerprints. It checks Flyway and pgvector and removes only its created schema/database. The workflow retains `restore-drill.log` and shell regression results with the test artifacts. This provides logical fixture restore evidence after the step actually passes; WAL/PITR, production-sized RPO/RTO, Git/Zoekt/Ollama volume restoration and post-restore HTTP MCP remain separate operator drills.

Local shell boundary tests: `python3 -B -m unittest discover -s scripts/tests -v`. These use controlled executable fixtures to verify corrupt/missing checksums, dump cleanup, permissions and command/schema failures. They complement the actual PostgreSQL CI drill.
