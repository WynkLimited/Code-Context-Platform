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
- **Flyway/schema:** inspect application startup and `postgres-integration-maven.log` for the first migration exception. The acceptance suite targets V1–V15 and pgvector; V7 adds durable contract-refresh jobs, V8 adds webhook delivery deduplication, and V9 adds renewable PostgreSQL repository-index leases.
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


Fallback FILE chunks use at most 60 physical source lines with up to ten lines of overlap, additionally bounded by `MAX_CHUNK_CHARS`. Java structural chunks retain method/class boundaries. Generator 1.10.0 includes this window change and uses the same safe reindex/publication rules described above. Generator 1.11.0 adds document sections: Markdown headings use CommonMark source spans (excluding headings in code/quotes); large YAML/JSON mappings use top-level source sections. Small configuration stays one character-bounded chunk. Malformed/nonmapping documents retain fallback windows. These parsers do not execute configuration or fetch referenced URLs. Exact tokenizer budgets and tiny structural merges remain outstanding.


## Embedding-space provenance and safe reuse (V12)

Each new generation stores an embedding fingerprint and `embedding_metadata`: provider, canonical model name, actual Ollama model digest, dimensions, context, truncation=false and request-policy version. `/api/tags` must expose exactly one matching configured model with a SHA-256 digest. Its absence or ambiguity fails indexing/search visibly. The application checks identity before and after indexing/search; do not pull, replace or retag a serving model during these operations. Provision models before rollout and keep all replicas aligned. Before/after checks cannot make concurrent administrative tag changes transactional.

V12 adds nullable provenance for existing generations without altering old migrations or deleting vectors. Reindex all repositories after upgrade or any model/digest/options change. Legacy/mismatched semantic indexes deliberately return a reindexing error; contract/text tools and the last successful published generation remain available under their normal rules. A same-commit reindex rebuilds vectors when embedding identity differs. During a controlled model rollout, complete reindexing before reopening semantic search to users. Existing-dimension changes additionally require the documented pgvector dimension migration/rebuild; recording a new identity alone does not alter vector column dimensions.

Within an edited file, only chunks whose full text/hash, repository, prior generation and embedding identity match reuse old vectors. Source coordinates and graph nodes are recomputed. Changed/new chunks are embedded, deleted chunks disappear with generation publication, and failed candidates cannot replace the published baseline. There is no cross-repository vector reuse. Inspect provenance with `SELECT id,repository_id,commit_sha,embedding_fingerprint,embedding_metadata FROM index_generations WHERE published;` using an authorized operator connection.


## Semantic declaration attribution

Generator 1.12.0 records Java declaration origin and JVM binary class name in semantic call-site evidence. Source/JDK runtime declarations are not attributed to dependency JARs merely because a JAR contains the same type. JAR attribution follows the actual JavaParser classpath order; nested binary names use `$`. Unknown ownership remains unset. Reindex earlier generations to refresh this evidence. The indexer's modeled classpath is not proof of arbitrary production classloader/plugin/module-path behavior; unsupported build semantics remain coverage limitations.


## Full-application replica acceptance

The mandatory `ApplicationReplicaConsistencyPostgresIT` report requires two named passed, unskipped cases (publication consistency and full publisher-process death recovery). It starts two independent full Spring JVMs and checks shared queue contention, production heartbeat renewal, six-tool HTTP registration, old-generation reads while a real candidate Zoekt shard waits to publish, and consistent new-commit reads/search after atomic publication. Child logs are uploaded from `target/replica-process-logs/**`. Both use a disposable UUID database schema and the CI services; only fixture-owned shards/schema/processes are removed. These add two PostgreSQL cases; all ten PostgreSQL cases and the separate HTTPS Maven case remain mandatory.

For manual execution, use the same Java21, PostgreSQL17+pgvector, Zoekt and Ollama prerequisites/environment as the full workflow, then `mvn --batch-mode --no-transfer-progress -Ppostgres-integration -Dtest=ApplicationReplicaConsistencyPostgresIT verify`. Inspect `target/surefire-reports/TEST-com.wynk.codecontext.index.ApplicationReplicaConsistencyPostgresIT.xml`: exactly the two named test cases, no failure/error/skipped nodes. Child readiness, job and HTTP waits are bounded; a failure must retain both process logs. This fixture verifies two applications sharing one host filesystem/service set; full-application crash/network faults and real multi-host Kubernetes storage validation remain separate requirements.


## Job SHA and successful-generation audit (V13)

Index requests refresh the registered branch after synchronization. `requested_commit_sha` preserves the triggering SHA or symbolic HEAD request; `commit_sha` records the actual snapshot once resolved. Both appear on `GET /api/index-jobs/{id}` together with `generation_id` and `generation_provenance_unverified`. Successful publication and same-commit reuse associate a job with its exact published generation under repository, SHA and attempt guards. Queued requests can coalesce onto a newer branch head; per-event pinned-commit execution is not promised.

V13 adds these fields without rewriting older migrations or inventing historic associations. Legacy successful jobs with unknown generation proof are excluded from successful-baseline inference. Retention conservatively holds temporally plausible historical generations while a legacy successful job remains unverified; new generations after that job's recorded completion do not inherit the hold. Review legacy holds against backups/audit evidence before authorizing release. Clearing `generation_provenance_unverified` on an unassociated legacy job releases its retention hold; it does not certify a generation or create a baseline.

Before rollout, back up and drain old indexing workers, apply the forward migration with the normal runtime role, start homogeneous application versions, and rebaseline repositories through normal indexing. A previously unverified historical baseline can remain unavailable until newly proven generations exist; impact responses must retain their missing-baseline limitation. Inspect with `SELECT id,requested_commit_sha,commit_sha,generation_id,generation_provenance_unverified,state FROM index_jobs ORDER BY created_at DESC LIMIT 50;`. Production restore/mixed-version rollout and legacy-history review remain operator validation tasks. The acceptance suite includes a real V12→V13 upgrade fixture, manual HTTP indexing/status, same-SHA failed-publication recovery and retention-hold checks; require their actual CI success before rollout.


The process-death case kills the complete publisher JVM only after real embedding, persistence and Zoekt indexing have reached the publication latch. Its test-only profile uses the existing short-duration lease constructor (three-second lease, 200 ms heartbeat); production defaults remain five minutes/thirty seconds and the consistency case still exercises that heartbeat. After verifying process death, the test ages only that job's heartbeat timestamp beyond the normal stale threshold. Production polling must expire/reclaim the same job, wait for natural lease expiry, retry at attempt two, and publish a different generation. No test writes job success or generation publication. Authenticated HTTP MCP must then read/search the recovered commit and identify the genuine older baseline through impact_analysis. This verifies recovery with injected heartbeat age; it does not measure the production ten-minute detection interval or production RTO/RPO. Network-partition and real cross-host storage drills remain separate.


The `GraphifyPostgresIT` gate now requires **two exact named, passed, unskipped cases**: `runsPinnedGraphifyCliAndPersistsGenerationBoundNodesAndEdges` and `indexesStructuralChunksThroughGraphifyOllamaPostgresAndHttpMcpThenReusesAndDeletesVectors`. The latter runs the real indexing pipeline with Graphify enabled, real Ollama/pgvector and authenticated HTTP MCP; it verifies structural Java/Kotlin/Python/TypeScript, merged tiny members, bounded Unicode-method inputs and ranges, SHA-256/vector provenance, one-input re-embedding and deletion through the same MCP session. Run with the existing `mvn --batch-mode --no-transfer-progress -Ppostgres-integration verify` after provisioning the documented real services. It is compiled locally but is not accepted until actual exact-commit CI reports both cases without failures/errors/skips. A green unit suite alone does not satisfy this gate.


## Automatic onboarding and webhook status (V14)

`POST /api/repositories` requires an administrator `X-API-Key` and the existing JSON fields (`owner`, `name`, `cloneUrl`, optional `defaultBranch`, `type`). Omitting `defaultBranch` discovers the remote symbolic HEAD. Read-only ref advertisement verifies access before registration; no repository build script runs during discovery. Registration, team access and one durable initial job commit together. The normal indexing worker creates the mirror and publishes its generation; a second manual `/index` request is unnecessary. Concurrent identical requests retain the same repository and initial job; conflicting active configuration returns409 rather than silently changing it.

Poll `GET /api/repositories/{owner}/{name}/onboarding` with an authorized team key. `INDEXING`, `INDEXING_FAILED`, `WEBHOOK_ACTION_REQUIRED`, and `INDEXED` describe workflow state; `INDEXED` is not production certification or a promise that every optional extractor is enabled. The response includes the exact initial job, published job generation and separate webhook state. Local `file:` fixtures report `NOT_APPLICABLE` for webhooks, never a fabricated provider success. Existing administrative/manual index APIs remain available. After correcting a permanent indexing or webhook error, an authorized administrator can call `POST /api/repositories/{owner}/{name}/onboarding/retry`; already queued initial jobs are not duplicated.

Per-repository GitHub webhook management is opt-in through **separate** `GITHUB_WEBHOOK_TOKEN` and `GITHUB_WEBHOOK_BASE_URL=https://<reachable-platform-host>` configuration. The token must be independently approved for repository Webhooks write; keep the existing Git content token read-only. Do not automatically reuse `GIT_ACCESS_TOKEN` or developer `gh` credentials. `GITHUB_API_BASE_URL` defaults to `https://api.github.com`; `GITHUB_API_ALLOWED_HOSTS` defaults to `api.github.com`. Configure approved Enterprise API hosts explicitly. HTTP redirects are refused, request/body deadlines are bounded and responses are capped at1MiB. Provider error bodies and token values are not stored in onboarding status.

The reconciler subscribes to `push`, uses JSON and TLS verification, and supplies the current `WEBHOOK_SECRET` on both creation and update. It enumerates existing callback matches before creating, so a crash/lost POST response is recovered by discovery and PATCH. Multiple matching hooks fail visibly rather than deleting unrelated provider resources. Durable claims expire after10minutes; repository leases fence competing workers. Failed attempts retry at1minute, stop after5 failures, and expose an explicit retry action. Confirmed hooks reconcile hourly. Missing management configuration remains `BLOCKED` while initial indexing can still progress.

Central GitHub App delivery is accepted at `/webhooks/github`; it shares HMAC verification, persisted delivery IDs and enqueueing with the legacy per-repository route. This ingress support does **not** certify App installation/token provisioning or central callback configuration. Do not provision per-repository hooks when an approved central App already supplies delivery; leave per-repository management credentials unset. Central App credential/configuration support is described below; webhook removal/lifecycle changes, general missed-event reconciliation and live GitHub push acceptance remain pending. Never mark live delivery verified from the controlled-provider test.

Before production use, complete OS-level execution isolation and the remaining release gates. The new workflow does not add Gradle or repository-controlled build-script execution; existing compiler/parser isolation limitations remain release blockers.


## Central GitHub App authentication and verification (V15)

Use a dedicated App with read-only Contents and Metadata, subscribed to `push`, installed on the approved repositories. Configure `GITHUB_APP_CLIENT_ID` (or numeric `GITHUB_APP_ID`) and `GITHUB_APP_PRIVATE_KEY_FILE` pointing to an operator-mounted RSA PKCS8 PEM key outside repository/source storage. Convert a GitHub RSA PEM to PKCS8 in the operator's secret-management environment if needed; never place keys in this repository or image. Startup rejects incomplete or invalid key configuration, keys below2048bits and source-controlled key locations, including symlink aliases. Production may use this App instead of `GIT_ACCESS_TOKEN`; existing strong team/admin/webhook-secret/origin/database checks still apply. The execution-isolation release blocker remains separate and must be completed before production processing of untrusted repositories.

App mode mints a token with explicit read-only permissions and one requested repository, then checks the resulting permission set and actual accessible repository list. Tokens are not written to PostgreSQL, source snapshots or logs; in-memory cache retention is at most5minutes and shorter than provider expiry. App JWTs have a ten-minute bounded claim interval. A configured App takes precedence over per-repository management credentials and never falls back to broader Git credentials or creates duplicate repository hooks.

Configure the App's central callback to `GITHUB_WEBHOOK_BASE_URL` plus `/webhooks/github`, JSON content type, TLS verification enabled and the platform's `WEBHOOK_SECRET`. The platform reads and validates that existing App configuration and installation; it does not silently rewrite App-global settings. On public GitHub, pair `github.com` with `https://api.github.com`. For supported GitHub Enterprise use the same approved host for clone and `https://<host>/api/v3` API access, with explicit Git/API host allowlists. Different provider/proxy host mappings fail closed rather than sending credentials to an unrelated API.

Onboarding status includes persisted `webhook_mode`, configuration verification and separate `webhook_delivery_verified`. Provider API confirmation cannot verify the masked secret. A new HMAC-validated push after the current configuration is confirmed establishes signing-key agreement; old rows without key provenance do not. Changed mode/callback/secret invalidates the old proof. Signed ping is accepted without indexing and is not treated as a push-delivery proof. Verify live GitHub delivery using an approved isolated repository and reachable callback before claiming provider acceptance. Current local/CI controlled-provider and signed-HTTP fixtures are not that certification.

After hook establishment, a fenced transaction enqueues a configuration-keyed HEAD catch-up job so a push during initial indexing/webhook creation is not silently lost. Existing generation publication/fencing and last-successful generation semantics apply. General missed-event polling, repository lifecycle hook removal, and App installation/repository-revocation events remain pending until their own implementation and acceptance.

Provider API contracts: [repository webhook permissions](https://docs.github.com/en/rest/repos/webhooks), [App JWT authentication](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-json-web-token-jwt-for-a-github-app), [App webhook configuration](https://docs.github.com/en/rest/apps/webhooks).


## Repository configuration transitions (V16)

An owning-team administrator may `PATCH /api/repositories/{owner}/{name}` with changed `owner`, `name`, `cloneUrl`, `defaultBranch`, `type`, or `team`, and the observed `expectedRevision`. HTTPS clone identity must match the provider namespace; target teams require an administrator key. The response is202 with the stable repository UUID, incremented revision, visibility state and tracked index job. A stale revision or conflicting in-flight transition returns409. Unchanged metadata does not create another transition.

Poll `GET /api/repositories/by-id/{uuid}/lifecycle` with the current owning team's administrator key. Check `enabled`, `desiredEnabled`, `indexJob.state` and transition state separately: preparation completion is not indexing success. During preparation the repository is hidden, old jobs are cancelled, and stale generation publication is rejected by a database configuration-revision fence. Existing repository leases defer physical mirror cleanup. Cleanup verifies the old mirror's origin and uses the durable generation-cleanup outbox for old search shards. Team transfer revokes the former team's access.

`DELETE /api/repositories/{owner}/{name}` remains soft disablement. Preparation must not re-enable an explicitly disabled repository. An explicit subsequent registration reactivates the same UUID through a new configuration revision and indexing job. After correcting a preparation failure, the current owning administrator may `POST /api/repositories/by-id/{uuid}/lifecycle/retry`; inspect status afterward rather than editing revision/fencing columns manually.

Owned per-repository hook retirement is reported separately. Missing authorization or ownership provenance remains visible as BLOCKED; central App hooks are never deleted by repository retirement. Do not equate controlled-provider fixtures with live GitHub permission or delivery certification. Namespace contention, provider-call/configuration concurrency, missed-event reconciliation and installation-revocation behavior still require further implementation/acceptance before lifecycle closure.


Repository registration rejects a case alias of an existing provider namespace, including soft-disabled registrations. Use the exact registered owner/name for API paths; the platform preserves that spelling. Stable-UUID lifecycle updates may change spelling, but cannot claim another UUID's alias. Pending old mirror paths remain reserved across case variants until preparation completes. Pre-existing ambiguous legacy aliases require explicit operator reconciliation; this guard does not merge repository history or IDs.


Webhook reconciliation serializes provider calls with repository configuration updates. A configuration PATCH or soft disable can wait for an in-flight bounded provider request before its metadata change is acknowledged; hard purge retains its existing busy-lease rejection. The repository lease heartbeat continues independently; provider error bodies and credentials are not persisted. After a client-side timeout, reload the lifecycle revision/status before retrying. Remote provider success followed by database failure still requires the durable discovery/recovery path; a successful controlled-provider fixture is not live-provider certification.


Subsequent webhook reconciliation is pinned to the confirmed immutable GitHub repository ID. If a namespace is reused for another repository, onboarding reports FAILED with the `ProviderIdentityChanged` category; no new hook receives the signing secret and existing verification provenance is retained. Investigate with the Git provider administrator before retrying. Changing credentials, callback settings, team or branch does not authorize rebinding. An authorized clone-URL lifecycle change preserves old-hook retirement provenance and invalidates the binding for the new URL. For an unrelated replacement at an unchanged URL, use an explicitly reviewed purge/new registration rather than silently treating the replacement as the previous repository.


## Missed-push reconciliation (V17)

Every enabled repository has a durable `refresh_due_at`. After the initial five-minute grace, `RepositoryRefresh` queues a HEAD check at the configured `REPOSITORY_REFRESH_INTERVAL_SECONDS` (default300, allowed30..86400). `REPOSITORY_REFRESH_POLL_MS` defaults to60000. Each poll schedules at most10 repositories. PostgreSQL row locks and a single enqueue/schedule transaction coalesce competing replicas; an active queued/running index job or pending lifecycle preparation defers another check. Disabled repositories are excluded. This is fallback recovery, not a replacement for prompt signed webhook delivery or a measured indexing-latency guarantee.

The normal indexing workers fetch the configured branch, reuse a valid current generation when appropriate, and retain existing retry, lease/configuration fences, last-successful publication and failure visibility. A scheduling transaction failure advances neither the due time nor the job queue. A failed indexing job remains visible; a later scheduled check can recover after the external cause is corrected. Size the cadence/poll rate for the number of repositories and actual worker capacity.

Authorized onboarding status includes `refresh_due_at`, `last_refresh_scheduled_at`, `last_refresh_job_id`, `last_refresh_state` and `last_refresh_error`; MISSING means referenced history was removed, not successful reconciliation. Identity-drift detection soft-disables visibility and indexing until explicit authorized recovery; a scheduled refresh cannot re-enable it. Lifecycle transitions invalidate the previous webhook claim so a late failure cannot suspend a new configuration.
