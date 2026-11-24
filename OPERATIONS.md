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

## PostgreSQL shared-library acceptance gate

The GitHub Actions workflow is named **CI** and is defined in [`.github/workflows/ci.yml`](.github/workflows/ci.yml). It supports manual dispatch and runs two distinct gates: the default unit/regression suite, followed by `mvn --batch-mode --no-transfer-progress -Ppostgres-integration verify`, which selects `SharedLibraryImpactPostgresIT` through Surefire. A green unit-test step alone does not close the PostgreSQL acceptance gate.

### Run from GitHub Actions

1. Ensure the desired commit, including the workflow and integration test, is pushed to the repository. Manual dispatch only runs content present on the selected branch; local uncommitted changes are not included.
2. Open the repository's **Actions** tab, select **CI**, choose **Run workflow**, select the branch containing the desired commit, and start the run.
3. Confirm the **Unit and regression tests** and **PostgreSQL V1-V8 shared-library impact integration** steps both pass. The latter starts `pgvector/pgvector:0.8.1-pg17` as a PostgreSQL service, waits for `pg_isready`, starts the pinned Zoekt and Ollama services, and uses database `code_context_test` with the test-only credentials configured in the workflow.
4. In the run summary, confirm separate unit/regression and PostgreSQL acceptance summaries. The acceptance report gate requires exactly one testcase whose class is `SharedLibraryImpactPostgresIT`, and it requires `1 passed, 0 failures, 0 errors, 0 skipped`. Confirm the summary shows the intended branch and commit SHA. A missing or malformed XML report fails the workflow.
5. Download **`java-and-postgres-test-reports`**. Inspect `target/surefire-reports/TEST-com.wynk.codecontext.index.SharedLibraryImpactPostgresIT.xml` and its matching `.txt` file, plus `target/postgres-integration-maven.log`, `target/unit-tests-maven.log`, and `target/acceptance-run-metadata.txt`. GitHub Actions logs are also retained with the run.

The acceptance test must apply migrations through V8 and verify the `vector` extension, durable bounded consumer-refresh queue, and GitHub delivery deduplication table. It indexes provider v1/v2 plus three real local Git consumer fixtures, checks REST/Kafka contract persistence and links, V4 call sites, V5 semantic edges, V6 Maven dependencies/classpaths, exercises source change/addition/deletion and compares incremental records with a forced full reindex, runs exact and regex Zoekt search and confirms the previous provider-generation shard is removed after publication, verifies Ollama embeddings and pgvector retrieval, invokes all six MCP tools over Streamable HTTP, expects only consumer A's v1 `Money.convert(String)` as a semantic method-impact result, and checks that an injected pre-publication failure retains the prior successful generation. A passing result has exactly one executed acceptance test with no skip, failure, or error.

### Diagnose a failed run

- **PostgreSQL readiness:** inspect the service container startup and `pg_isready` health status in the job log. The integration cannot be skipped when the database is unavailable.
- **Flyway/schema:** inspect application startup and `postgres-integration-maven.log` for the first migration exception. The acceptance suite uses V1-V8 and pgvector; V7 adds durable contract-refresh jobs and V8 adds webhook delivery deduplication.
- **Maven/build:** inspect the corresponding Maven log and the failing workflow step. Java setup is Temurin 21; dependency/build failures fail the job.
- **Test assertion:** read the Surefire `.txt` and XML report for assertion/exception details. The workflow also fails if the named acceptance test report is missing, skipped, or has anything other than one test case.
- **Run identity:** use the workflow summary and `acceptance-run-metadata.txt` for branch, commit SHA, workflow URL, Java version, database image and exact Maven command.

The latest local environment has Java 17 only, no reachable Docker daemon, and invalid GitHub CLI authentication. No Java 21 build or PostgreSQL-backed acceptance execution is claimed locally; see [IMPLEMENTATION_REPORT.md](IMPLEMENTATION_REPORT.md) and [PRODUCTION_READINESS_MATRIX.md](PRODUCTION_READINESS_MATRIX.md) for the current evidence state.
