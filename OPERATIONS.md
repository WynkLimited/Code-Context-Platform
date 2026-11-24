# Operations runbook

## Health

- Liveness/readiness: `/actuator/health/liveness`, `/actuator/health/readiness`.
- Metrics: `/actuator/metrics`; expose Prometheus scraping only inside the platform network.
- Job state: `GET /api/index-jobs/{uuid}` with a team API key.
- Application logs include job failures and dependency error messages. Do not log source-file contents or tokens.

## Routine operations

1. Check PostgreSQL, Ollama, Zoekt, and app readiness.
2. Inspect `FAILED` and aged `RUNNING` rows; fix the external cause before retrying with the repository reindex endpoint.
3. For a manual refresh, call `POST /api/repositories/{owner}/{name}/index`.
4. Follow log output during initial indexing; large repositories can take substantial time because embeddings and Graphify are resource intensive.

## Failure recovery

- PostgreSQL outage: restore the database from backup; re-run indexing for repositories whose published generation lacks the restored Git commit.
- Ollama outage/model mismatch: restore the configured model and dimensions, then reindex only after verifying dimensions match the Flyway vector column.
- Zoekt outage: repair the index volume and requeue repositories. The database generation may remain published while Zoekt indexing failed; current code marks that job failed and retains DB generation as unpublished if the new index had not committed.
- Git checkout failure: verify the URL allowlist, installation token, repository permission, and configured default branch.
- Graphify failure: inspect the per-job `.graphify-index.log` in the temporary snapshot before cleanup; use `GRAPHIFY_ENABLED=false` only for development diagnosis, not acceptance signoff.

## Rollback

Do not delete a previous published generation until the new generation has passed MCP search and exact file-read checks. The target currently retains database generations. Generation-specific Zoekt indexes can be switched by restoring the prior database publication pointer in a transaction, but an automated rollback command is not implemented yet.


## Impact analysis interpretation

`impact_analysis` is informational and always reports `blocking=false`. Java method call-site rows are deterministic syntax matches in the consumer source at the indexed commit. They identify candidate class/method and source lines; they do not prove overload resolution, signature incompatibility, or behavioral breakage. Treat `INCOMPLETE`, `STALE`, ambiguous bindings, and parser failures as missing evidence, never as an empty-consumer conclusion. Kotlin method-level analysis and behavioral evaluation are not currently implemented.

The local verification run passed 120 unit/regression tests on Java 21. Docker was unavailable, so PostgreSQL/pgvector, Zoekt, live MCP, full restart/recovery, performance, and security acceptance checks remain open.
