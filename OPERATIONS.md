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

`impact_analysis` is informational and always reports `blocking=false`. Exact resolved method impacts use persisted V5 edges and Maven classpath evidence at the indexed commit; syntax-only evidence remains separate. They identify resolved signatures and potential upgrade impact, without proving current dependency or behavioral breakage. Treat `INCOMPLETE`, `STALE`, ambiguous bindings, and parser failures as missing evidence, never as an empty-consumer conclusion. Kotlin compiler JVM edges and ABI compatibility evidence are supported; Kotlin source compatibility and behavioral evaluation remain unknown. Bounded intra-repository caller chains are advisory reachability evidence, while arbitrary cross-repository method-chain propagation remains unsupported.

## PostgreSQL shared-library acceptance gate

The GitHub Actions workflow is named **CI** and is defined in [`.github/workflows/ci.yml`](.github/workflows/ci.yml). It supports manual dispatch and runs two distinct gates: the default unit/regression suite, followed by `mvn --batch-mode --no-transfer-progress -Ppostgres-integration verify`, which selects `SharedLibraryImpactPostgresIT` through Surefire. A green unit-test step alone does not close the PostgreSQL acceptance gate.

### Run from GitHub Actions

1. Ensure the desired commit, including the workflow and integration test, is pushed to the repository. Manual dispatch only runs content present on the selected branch; local uncommitted changes are not included.
2. Open the repository's **Actions** tab, select **CI**, choose **Run workflow**, select the branch containing the desired commit, and start the run.
3. Confirm the **Unit and regression tests** and **PostgreSQL V1-V11 shared-library impact integration** steps both pass. The latter starts `pgvector/pgvector:0.8.1-pg17` as a PostgreSQL service, waits for `pg_isready`, starts the pinned Zoekt and Ollama services, and uses database `code_context_test` with the test-only credentials configured in the workflow.
4. In the run summary, confirm separate unit/regression and PostgreSQL acceptance summaries. The acceptance report gate requires exactly three testcases whose class is `SharedLibraryImpactPostgresIT` (Java Money impact, REST ambiguity and Kotlin provider cross-language impact), and it requires `3 passed, 0 failures, 0 errors, 0 skipped`. Confirm the summary shows the intended branch and commit SHA. A missing or malformed XML report fails the workflow.
5. Download **`java-and-postgres-test-reports`**. Inspect `target/surefire-reports/TEST-com.wynk.codecontext.index.SharedLibraryImpactPostgresIT.xml` and its matching `.txt` file, plus `target/postgres-integration-maven.log`, `target/unit-tests-maven.log`, and `target/acceptance-run-metadata.txt`. GitHub Actions logs are also retained with the run.

The acceptance test must apply migrations through V11 and verify the `vector` extension, durable bounded consumer-refresh queue, GitHub delivery deduplication table, and per-repository lease mutual exclusion/release. It indexes provider v1/v2 plus three real local Git consumer fixtures, checks REST/Kafka contract persistence and late-provider relationship binding without consumer reindexing, V4 call sites, V5 semantic edges, V6 Maven dependencies/classpaths, exercises source change/addition/deletion and compares incremental records with a forced full reindex, runs exact and regex Zoekt search and confirms the previous provider-generation shard is removed after publication, verifies Ollama embeddings and pgvector retrieval, invokes all six MCP tools over Streamable HTTP, expects only consumer A's v1 `Money.convert(String)` as a semantic method-impact result, and checks that an injected pre-publication failure retains the prior successful generation. The shared-library report requires exactly three executed cases with no skip, failure or error; the four additional PostgreSQL classes each require exactly one successful case.

### Diagnose a failed run

- **PostgreSQL readiness:** inspect the service container startup and `pg_isready` health status in the job log. The integration cannot be skipped when the database is unavailable.
- **Flyway/schema:** inspect application startup and `postgres-integration-maven.log` for the first migration exception. The acceptance suite uses V1-V11 and pgvector; V7 adds durable contract-refresh jobs, V8 adds webhook delivery deduplication, and V9 adds renewable PostgreSQL repository-index leases.
- **Maven/build:** inspect the corresponding Maven log and the failing workflow step. Java setup is Temurin 21; dependency/build failures fail the job.
- **Test assertion:** read the Surefire `.txt` and XML report for assertion/exception details. The workflow also fails if the named acceptance test report is missing, skipped, or has anything other than three successful shared-library cases.
- **Run identity:** use the workflow summary and `acceptance-run-metadata.txt` for branch, commit SHA, workflow URL, Java version, database image and exact Maven command.

The latest local environment has Java 17 only, no reachable Docker daemon, and invalid GitHub CLI authentication. No Java 21 build or PostgreSQL-backed acceptance execution is claimed locally; see [IMPLEMENTATION_REPORT.md](IMPLEMENTATION_REPORT.md) and [PRODUCTION_READINESS_MATRIX.md](PRODUCTION_READINESS_MATRIX.md) for the current evidence state.

## Generation retention and cleanup

V10 adds `generation_cleanup_outbox`. Configure `GENERATION_RETENTION_DAYS` (minimum 1, default 30), `GENERATION_GC_BATCH_SIZE` (1–100, default 10), and `GENERATION_GC_POLL_MS` (default 60000). Current and two newest successful generations are retained regardless of age. Saved impact analyses, refresh references, contract links, and queued/running jobs prevent deletion. This deliberately retains evidence indefinitely when references remain.

PostgreSQL cascade deletion and the cleanup outbox commit atomically. External Zoekt removal follows and is retried after failures or crashes. Inspect `generation_cleanup_outbox` for `state='FAILED'`; after resolving the external cause, explicitly reset the affected row to `state='QUEUED',attempt=0,available_at=now()` to retry. Do not delete the outbox row to resolve a failure. Soft disablement preserves published/baseline data for re-onboarding; this is not a permanent repository purge operation. Multi-instance retention and crash tests must pass before increasing replicas.

## Shared authenticated request budgets

Set `RATE_LIMIT_PER_MINUTE` to the required positive team budget (default `0` preserves disabled limiting). `RATE_LIMIT_STORE=postgres` is the default backend: replicas sharing this database coordinate atomically using PostgreSQL time and V11 `api_rate_limit_windows`. Apply Flyway V11 before enabling the budget. All replicas must use the same budget configuration. Rotating API keys for a team retains that team's window. Only team names, minute timestamps and counters are stored; API keys are never stored in the budget table. One row per team is overwritten across windows rather than appending request history. After permanently retiring a team, an operator may delete its obsolete row.

Exhaustion returns HTTP 429 with `Retry-After` and successful requests include `X-RateLimit-Remaining`. Storage failure returns HTTP 503 with a generic message and `Retry-After: 1`; requests are rejected rather than falling back to a per-process budget. Investigate database connectivity, pool saturation and permissions; the server log emits `Rate-limit storage unavailable; authenticated request rejected` without credentials. Health probes and separately authenticated webhooks retain their existing exclusions. `RATE_LIMIT_STORE=memory` is available only for an explicitly single-process deployment and does not coordinate replicas. The limiter counts initial authenticated HTTP requests, not repeated async/error servlet dispatches; a request opening an MCP stream does not budget each future message on that stream. Choose ingress and tool execution limits for that separate capacity concern.

`ProductionDatabaseLeastPrivilegeIT` verifies migration V11 under a restricted database role, concurrent independent gate instances, team isolation, restart persistence, saturation and expired-window reset against actual PostgreSQL. Unit tests cover HTTP 429/503, safe errors and backend configuration. This does not measure a representative production request rate.

## HTTP correlation and structured logs

Console logs default to Spring Boot's Logstash JSON formatter (`LOG_FORMAT_CONSOLE=logstash`). Each servlet request receives a server-generated UUID in `X-Request-ID`; client-supplied IDs are ignored. The same ID is reused on ASYNC and ERROR redispatches and appears as `request_id` in logs on that dispatch thread. The filter runs before authentication, so 401/403 responses can be correlated too. It restores prior MDC context on every exit, including exceptions and nested error dispatches.

`http.dispatch.finished` records dispatcher type, status at that point, async-started state and elapsed milliseconds. It does not log request headers, method arguments, URLs, query strings, API keys or source text. An initial async dispatch finishing is not the same event as the final streaming request completing. Arbitrary Reactor schedulers and tool worker threads do not automatically inherit servlet MDC. Durable indexing attempts instead establish their own scoped `job_id`, `repository_id` and `job_attempt`, adding the actual synchronized `commit_sha` and new `generation_id` as those become available. `index.job.dispatch.started`/`finished` delimit the worker dispatch; finished includes lease contention, retries and failure, and does not itself mean publication succeeded. Worker scope discards unrelated inherited MDC and restores the previous thread context on every exit; it does not copy HTTP headers or source into logs. No distributed tracing or complete end-to-end correlation is claimed. The PostgreSQL HTTP MCP fixture checks UUID response headers for authenticated and rejected calls; local tests cover context cleanup, redispatches, nested errors, safe log content and fresh request identity.

## OpenAPI enum compatibility scope

REST extractor 1.4.0 with compatibility rules 1.8.0 preserves explicit string/integer/number/boolean enum values in deterministic `enum64~` signature tokens. Request narrowing and response widening report BREAKING; inverse movement is compatible within that scalar domain. Required-field changes are evaluated too. Reindex older stored generations before relying on these rules. Null/mixed/object domains, additional unmodelled scalar validation and oversized enums remain UNKNOWN and incomplete; nested array enum compatibility also remains UNKNOWN. These verdicts concern declared schema compatibility, not proven consumer runtime behaviour. Existing impact responses remain advisory.

## Maven repository-policy and model containment

Maven classpaths use only the ordered operator-configured `MAVEN_ALLOWED_REPOSITORIES` and `MAVEN_ALLOWED_HOSTS`; dependency artifact descriptors cannot add repositories. Cache files are stored below `.maven-cache/policy-3-<digest>`, keyed by policy epoch, repository IDs, ordered URLs (canonical file roots) and allowed-host sets. Changing a repository URL or policy selects a separate cache namespace even if its Maven ID stays the same. Earlier unscoped cache files are not reused by this epoch and are not automatically deleted. Keep cache directories writable only by the application/operator; retire unused namespaces through an operator-approved retention process.

Effective snapshot models are built once per POM per resolution. Parent relative paths can read only within the immutable snapshot; explicitly approved local parent/BOM roots are also contained and symlink ancestors are rejected. Models are bounded to 16 MiB and 128 XML levels and reject DOCTYPE, external entities and XInclude. These checks do not execute build plugins. HTTPS parents/BOMs now resolve only through approved repositories using TLS, strict checksums, redirect refusal and the same scoped cache. Imported models are cached only within that resolution and capped at 256 requests. Missing models remain incomplete. DNS-to-connect changes and OS process/filesystem isolation remain separate controls; this is not an OS sandbox.

Generator 1.8.0 invalidates older persisted contract generations so evidence produced under the previous resolver policy is not silently treated as fresh. Reindex each authorized repository using `POST /api/repositories/{owner}/{name}/index` with its team's API key, then inspect the returned job using `GET /api/index-jobs/{jobId}`. Require `SUCCEEDED` and current published-generation evidence before relying on impact results. Do not delete the last successful generation to force freshness.

### HTTPS model and integrity acceptance

Resolver runtime modules are aligned at 1.9.26, retaining Maven model-builder/provider 3.9.9. `aether.connector.http.followRedirects=false` is supported by this Resolver patch ([Apache configuration](https://maven.apache.org/resolver-archives/resolver-1.9.26/configuration.html)); configure final repository URLs directly. Checksums use FAIL policy: missing/mismatched downloaded artifact checksums produce incomplete resolution and cannot become trusted results on retry. Publishers must provide valid checksums; there is no warning-only fallback. Resolver metadata records `maven-resolver-1.9.26-policy-3`.

JDK/OS-activated profiles evaluate using only `java.version`, `java.specification.version`, `os.name`, `os.arch` and `os.version` from the indexer. Host environment/private JVM properties and local Maven settings are not supplied to interpolation. Unresolved coordinates remain incomplete. This is the indexer's build context, not proof that every consumer's CI uses identical profiles. Arbitrary user-activated profiles and authenticated private repository credentials still require additional support/validation.

CI additionally requires `MavenRemoteModelIT`: exactly one successful, unskipped case using actual allowlisted HTTPS Maven Central, an Apache parent, imported JUnit BOM, transitive dependencies and exact Java method binding against downloaded jars from an empty cache. It is a separate HTTPS integration case, not an additional PostgreSQL scenario; the seven PostgreSQL cases remain mandatory. Egress to `repo.maven.apache.org:443` and its public DNS resolution is a prerequisite for this gate. A local transport regression uses controlled HTTP servers to prove no redirect target is contacted; application URL guards continue to reject HTTP and loopback.


## Embedding input integrity and generator upgrades

`spring.ai.ollama.embedding.num-ctx` defaults to 8192 and is explicitly bound by `OllamaEmbeddingConfiguration` because Spring AI 2.0.2 does not expose it in embedding properties. Supported application bounds are 512–131072 tokens; the chosen model/server must support the configured context. `spring.ai.ollama.embedding.truncate` must remain false. Oversized requests fail visibly instead of silently discarding source. The single application model retains configured model, keep-alive, initialization policy and observation settings; generic embedding calls are adapted to preserve provider context options on the wire.

Generator 1.9.0 rebuilds cached source nodes/chunks when reindexing a generation created by an older generator, even when Git blobs match. Existing published data remains available until the replacement publishes successfully. Reindex repositories after upgrading; old contract evidence remains stale under the normal freshness checks. Java chunks use original source text and physical line ranges. Character-bounded splitting is not an exact tokenizer budget: representative oversized inputs and model capacity still need pilot validation. Model tag/digest provenance and chunk reuse within edited files remain separate outstanding requirements.
