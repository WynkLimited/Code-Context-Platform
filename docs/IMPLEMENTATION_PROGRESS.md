## Current continuation checkpoint — 2026-11-26

Authoritative current status: `docs/PRODUCTION_READINESS_LEDGER.md`. Historical sections below describe earlier checkpoints and permissions. Current user authorization permits scoped validated commit/push to `fix/jackson3-postgres-acceptance` and CI dispatch; no production deployment or merge. `bee5d60` is verified green (237 unit + seven PostgreSQL cases including independent JVM recovery). Caller-chain ae417c1 and shared PostgreSQL request-budget 5c83562 CI both passed (242/244 unit + seven PG). Logical restore 0ec1591, correlation 6f3a0fb and rolling-budget/common JSON logging df9b83d CI passed. Scalar enum df26d13 CI passed (256 unit + seven PG). Maven policy 78f0339 CI passed (262 unit + seven PG). Secure HTTPS parent/BOM resolution, redirect denial and strict checksums pass 265 default Java21 tests plus real HTTPS integration locally; next exact-SHA PR CI adds a mandatory HTTPS report gate. Overall remains NO-GO. Continue the unresolved master directive requirements after validation.

# Implementation progress

Updated 2026-11-25 (evening resume). Overall decision: **NO-GO**. This is a resumable implementation checkpoint, not a production certification.

Baseline: branch `fix/jackson3-postgres-acceptance`, pushed HEAD `13fccca2b57f64b85a166268effa07746853a3f5`. Preserve all uncommitted work. Further commits/pushes are not authorized. Reference repository is read-only.

## Current slice

V9 renewable leases and V10 generation retention are committed in baseline `13fccca`. Publication now locks the ownership row inside its transaction (`Lease.fenceTransaction`); lease takeover cannot complete until that transaction ends. A pre-publication ownership check alone was insufficient and has been corrected.

`GenerationRetentionService` defaults to 30 days, batches of 10, polling every 60 seconds. It preserves published generations, the two newest successful generations, all generations referenced by saved impact analyses, refresh jobs, or contract links, and all generations of repositories with queued/running indexing jobs. Deletion and insertion into the external cleanup outbox commit together; derived PostgreSQL rows cascade. Zoekt cleanup retries exact generation UUIDs, with row locks, five-minute backoff and terminal FAILED after 10 attempts. Completed outbox history is retained. Soft-disabled repositories retain active/baseline evidence and retain their identity on re-onboarding. Hard deletion/purge is not implemented.

Tests: `GenerationRetentionPostgresIT` covers active/baseline protection, active jobs, impact references, graph/contract/semantic/nodes/chunks cascade and link protection, repeated collection, cleanup failure/restart retry, stale lease rejection, and transactional fencing under contention. It uses real PostgreSQL and a fault-injecting Zoekt adapter; actual Zoekt search/deletion remains in `SharedLibraryImpactPostgresIT`. These integration tests have **not executed locally**. Compilation is not integration evidence.

## Requirement ledger

Every row lists current evidence and the exact next action; rows remain incomplete until required service evidence exists. Source paths below are repository-relative.

| ID | Requirement | Status | Implementation / tests | Executed evidence and remaining action |
|---|---|---|---|---|
| DB-1 | PostgreSQL bootstrap/runtime separation, migrations | IMPLEMENTED AND INTEGRATION VERIFIED | `scripts/postgres-init-app-role.sh`, production check, `ProductionDatabaseLeastPrivilegeIT` | CI a176f6e: V1–V10 applied as restricted role; existing-volume ownership/rotation drill still open. |
| GC-1 | Configurable durable bounded retention | IMPLEMENTED AND INTEGRATION VERIFIED | `GenerationRetentionService`, V10, `GenerationRetentionPostgresIT` | CI a176f6e executed cascade/outbox; hard-purge + Zoekt reconcile extensions pending next CI. |
| GC-2 | Active, last-good and impact baseline protection | IMPLEMENTED AND INTEGRATION VERIFIED | Retention eligibility SQL + PostgreSQL test | CI a176f6e; multi-instance simultaneous query/index/cleanup still open. |
| GC-3 | External cleanup retry/recovery | IMPLEMENTED AND INTEGRATION VERIFIED | Cleanup outbox; fault-injection PG test; idempotent not-found Zoekt remove | CI a176f6e + unit; orphan reconciliation added locally pending next CI. |
| GC-4 | Disable/re-onboard/delete lifecycle | IMPLEMENTED AND INTEGRATION VERIFIED | `RepositoryStore.purge`, controller `?purge=true`, retention PG hard-purge/reclaim | CI b2346ee: soft disable + authorized hard purge + foreign-team reclaim. Cross-team re-onboarding ACL depth still open. |
| IDX-1 | Distributed lease acquisition/renewal/fencing | PARTIALLY IMPLEMENTED | V9, `RepositoryIndexLeaseManager`, publication transaction; PG contention test | Run PG tests, then crash/heartbeat failure and two-process publication tests. |
| IDX-2 | Durable jobs, retries, duplicate delivery | PARTIALLY IMPLEMENTED | `IndexingService`, V7/V8 refresh/webhook queues | Unit webhook suite passed; test stale worker job-state fencing and permanent-failure operations. |
| IDX-3 | Incremental/full rebuild equivalence | PARTIALLY IMPLEMENTED | `SharedLibraryImpactPostgresIT` addition/change/deletion/equivalence | Compiled only; run CI then add rename/dependency/graph equivalence cases. |
| SEARCH-1 | Zoekt exact/regex, evidence, update/deletion | IMPLEMENTED AND E2E VERIFIED | `ZoektSearchService`, `ZoektIndexer`, unit + SharedLibraryImpactPostgresIT | CI a176f6e real Zoekt exact/regex + generation deletion; query-scope adversarial unit-tested. |
| SEARCH-2 | Source→Ollama→pgvector→MCP | IMPLEMENTED AND E2E VERIFIED | Indexing chunks, `CodeContextTools`, shared IT | CI a176f6e real Ollama+pgvector+MCP semantic_search; oversized/model-error cases still open. |
| CONTRACT-1 | REST extraction/schema compatibility | PARTIALLY IMPLEMENTED | OpenAPI JSON+YAML `$ref`→`restOp` via SnakeYAML/`OpenApiSchemaSupport`, direction-aware request/response/param rules, config-derived routes; unit + shared IT evidence | YAML `$ref` unit green (extractor 1.2.0). Run PG shared IT for persisted OpenAPI schema evolution. |
| CONTRACT-2 | Kafka/Protobuf schemas | PARTIALLY IMPLEMENTED | Avro/JSON `fields{}` signatures, proto field-number reuse for Kafka, config topic placeholders; required Avro field add = BREAKING; unit tests | Unit suite green (rules 1.7.0); add multi-repo persisted Avro evolution + MCP consumer-impact fixture. |
| CONTRACT-3 | Configuration-derived relationships | PARTIALLY IMPLEMENTED | Nested YAML/properties placeholders for REST+Kafka; unit tests | Unit tests passed; add multi-repo persisted config-backed acceptance. |
| CONTRACT-4 | Unresolved/ambiguous relationship refresh | PARTIALLY IMPLEMENTED | Durable refresher + late-provider IT; link AMBIGUOUS/RESOLVED re-evaluation; GraphSymbolBinder rebind test | Unit binder test passed; run late-provider PG case; prove ambiguity demotion/resolution under contention. |
| JVM-1 | Java method resolution, Maven transitive ownership | PARTIALLY IMPLEMENTED | Java semantic analyzer, Maven resolver and unit tests | Unit tests passed; add transitive overlapping-class artifact-ownership fixture. |
| JVM-2 | Kotlin compiler method semantics, Java interop | PARTIALLY IMPLEMENTED | `KotlinJvmSemanticAnalyzer`, ABI contracts, Kotlin provider multi-repo shared IT | CI b2346ee: Kotlin provider ABI + K→K/J→K MCP impact green. Inline/suspend/default/nullability source mapping remains UNKNOWN; inter-module topology still open. |
| GRAPH-1 | Graphify graph and semantic symbol binding | IMPLEMENTED AND INTEGRATION VERIFIED | Graphify importer, binder, `GraphifyPostgresIT` | CI a176f6e real CLI ingestion; mixed-symbol binding depth still open. |
| MCP-1 | Six authenticated protocol tools | IMPLEMENTED AND E2E VERIFIED | Shared IT invokes all six tools + cross-team isolation + Kotlin-provider impact | CI a176f6e + b2346ee authenticated Streamable HTTP including Kotlin provider impact_analysis. |
| SEC-1 | Team/repository isolation and adversarial cases | PARTIALLY IMPLEMENTED | API filter, scoped stores/search unit tests | Add cross-team protocol tests for every tool and re-onboarding ACLs. |
| SEC-2 | Git/Maven SSRF, filesystem/subprocess protection | PARTIALLY IMPLEMENTED | Host allowlists, path guards, time/output limits | Add redirect/DNS/private-address and symlink adversarial tests; assess existing mirror origin validation. |
| SEC-3 | Credential rotation, rate limits, audit | PARTIALLY IMPLEMENTED | Dual-key `team=current\|previous` roster, rate-limit gate (default off), audit redaction | Unit rotation/revoke green; admin key-admin API and multi-replica limiter still open. |
| SEC-4 | Webhook replay protection | IMPLEMENTED AND UNIT TESTED ONLY | V8 and controller/filter tests | Unit tests passed; run durable PG replay/concurrency test. |
| OPS-1 | K8s security/resources/probes/egress | PARTIALLY IMPLEMENTED | `deploy/k8s`, Compose | YAML/Compose parsing passed; cluster smoke and egress enforcement require deployment environment. |
| OPS-2 | Shutdown/recovery/reconciliation | PARTIALLY IMPLEMENTED | Worker shutdown, lease expiry, cleanup queue | Add in-flight shutdown and generation reconciliation tests, including external index drift. |
| OPS-3 | Metrics, correlations, alerts | PARTIALLY IMPLEMENTED | Actuator/Prometheus; `PlatformMetrics` domain meters; `deploy/prometheus/alerts.yaml` | Unit meters + alert YAML green; scrape/dashboards and live alert firing still need ops environment. |
| OPS-4 | Backup/restore/disaster recovery | PARTIALLY IMPLEMENTED | `BACKUP_AND_RECOVERY.md`; `scripts/backup-postgres.sh`, `scripts/restore-smoke.sh` | Scripts + dry-run exist; execute restore/PITR drill and post-restore MCP smoke in isolated PostgreSQL environment. |
| OPS-5 | Preflight/smoke/performance | PARTIALLY IMPLEMENTED | CI service preflights and deployment docs | Add runnable deployment smoke/preflight and measure representative capacity. |
| VALID-1 | Java21/service acceptance | IMPLEMENTED AND E2E VERIFIED | CI profile plus strict XML gates | CI a176f6e success: 199 unit + 4 PG ITs. Local Docker still unavailable; further gates via workflow_dispatch. |

## Validation and next execution

- `mvn -Dmaven.compiler.release=17 test`: 145 passed, zero failures/errors/skips (supplemental only).
- Java 21 downloaded from Adoptium into ignored `target/jdk21/Contents/Home`. Java21.0.13.1 suite: 145 passed, zero failures/errors/skips; compilation log confirms release 21.
- `docker info`: cannot connect to daemon. `open -a Docker`: application not installed. No PostgreSQL CLI available. Do not claim service acceptance passed.
- Integration command: `JAVA_HOME="$PWD/target/jdk21/Contents/Home" PATH="$PWD/target/jdk21/Contents/Home/bin:$PATH" mvn --batch-mode --no-transfer-progress -Ppostgres-integration verify` with the PostgreSQL17+pgvector, Zoekt, Graphify and Ollama services/environment from `.github/workflows/ci.yml`.
- CI requires exactly one passed/unskipped result each for shared-library, least-privilege, Graphify and generation-retention IT classes.
- Next: run retention/fencing PostgreSQL tests in available service environment; fix defects before marking verified. Continue independent Kotlin semantic support, security and operational requirements without marking partial implementations complete.

Final local validation for this slice: fresh production and test compilation under Temurin 21.0.13.1 (release 21), then 145 unit tests passed with 0 failures/errors/skips. Latest expanded PostgreSQL test compiles under Java21; it remains unexecuted. JGit logged a sandbox-denied user config write; no test failed. CI YAML parses and `git diff --check` passes.


## Kotlin compiler checkpoint (2026-11-24)

Kotlin 2.2.21 compiler archive checksum verified against the upstream release: `a623871f1cd9c938946948b70ef9170879f0758043885bbd30c32f024e511714`. CI and Docker install this pinned compiler. The runtime image uses Java21 JDK for compilation. `KOTLIN_COMPILER` selects its executable. Local tests default to `target/kotlinc/bin/kotlinc`.

Immutable module snapshots now include tracked `.kt` sources. Kotlin compiler runs in a separate JVM (512MiB heap, 120-second deadline, bounded captured diagnostics), with explicit source/classpath arguments and no repository build/plugin execution. ASM reads emitted JVM declarations/invocations. JavaParser receives the emitted Kotlin jar for Java-to-Kotlin resolution. Persisted Kotlin evidence is tagged `kotlin-compiler-jvm`. Each Kotlin file also stores an INCOMPLETE marker: source-only types, inlined calls, generated/default/suspend constructs and exact source-to-JVM mapping are not fully reconstructed. Failed compilation persists diagnostics without invented resolved edges. The global generator version is bumped to 1.3.0 so older indexed snapshots are stale and need reindexing.

Executed: full Java21 suite with 148 tests passed (145 existing + 3 real compiler tests), zero failures/errors/skips. Compiler fixtures cover interfaces/classes, constructors, getters, extension/top-level functions, overload removal/change, Kotlin-to-Java and Java-to-Kotlin, pinned external provider jar versus upgraded jar and unresolved dependencies. These are local source/module fixtures, not cross-repository PostgreSQL acceptance. The shared acceptance Git fixture now adds mixed Kotlin/Java Consumer C files and exact persisted caller/target assertions; it remains unexecuted. The incremental snapshot assertion now preserves explicit `<unknown>` values for completeness-marker rows instead of failing on SQL NULL concatenation.

Not complete: Kotlin exported Maven ABI contracts/provider compatibility, indirect Kotlin callers, production cross-repository Kotlin impact, full Kotlin metadata/source semantic mapping, inter-module compilation topology, and external service validation. Do not equate compiler JVM evidence with complete Kotlin semantic intelligence. Priority 2–5 gaps in the ledger remain open.

Incremental limitation: Kotlin compiler analysis recompiles each affected module snapshot on every indexing generation; Java semantic analysis and Graphify also reconstruct their stage. No unchanged Kotlin semantic-row reuse is claimed. Final Java21 validation after generator-version and acceptance-fixture changes: 148 tests passed, 0 failures/errors/skips; PostgreSQL fixtures compiled but not executed.


## Actual CI execution and compiler isolation fixes — 2026-11-25

Remote branch SHA verified by `git ls-remote`: `13fccca2b57f64b85a166268effa07746853a3f5`. `gh run list --commit <full-sha>` initially returned no runs. CI push trigger is restricted to main; branch requires pull_request or workflow_dispatch. Manually dispatched existing workflow without changing triggers or committing/pushing.

Actual run: https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45558522188 (job 139016325629). Result **failure**. Setup-java resolved Temurin **21.0.13+9**. PostgreSQL service initialized using configured pgvector PG17 image; no migration/integration assertions executed. Unit Surefire XML: **148 total, 125 passed, 3 failures, 0 errors, 20 skipped**. All three failures are Kotlin compiler tests. Twenty skipped tests belong to `ProtoContractExtractorTest` (missing protoc). Zoekt/Ollama/Graphify setup and every integration gate were skipped after the unit failure. No service-backed component is verified by this run. Full logs and downloaded XML are retained locally under ignored `target/ci-13fccca*`.

Confirmed failure: `ClassNotFoundException: /home/runner/work/Code-Context-Platform/Code-Context-Platform/target/kotlinc/bin/kotlinc`. The app/CI variable `KOTLIN_COMPILER` selects the executable, but kotlinc's shell launcher also reads it as the Java compiler main class. Inheriting it into the child causes the compiler to load the executable path as a class. Child environment now uses an explicit allowlist and does not inherit that variable. It also removes JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, BASH_ENV, KOTLIN_OPTS and application credentials. Existing timeout and heap bound remain. Added a shared fair semaphore (two compiler processes, 30-second acquisition timeout), real-path checks rejecting symlink escapes, output containment, jar-only classpath and bounded source/output checks. Compiler raw diagnostics are withheld to prevent source or credential disclosure. Disk output size is checked after compilation; this is not a hard filesystem quota or OS sandbox. No source-level security sandbox is claimed.

CI now installs protobuf-compiler before unit execution and rejects all skipped unit reports. Five Kotlin tests run against the actual compiler: the original three resolution/version tests plus adversarial path escape/output and environment-injection tests. Reproducing the CI `KOTLIN_COMPILER` environment under Java21 gives **150 tests passed, 0 failures/errors/skips** locally. Focused Git test also passed after the subsequent origin validation change. No acceptance assertion was weakened.

Independent Git security fix: `GitRepositoryManager.sync` revalidates the registered URL on every refresh, requires exactly one mirror origin identical to the registered URL, and rejects mismatch before fetch. Existing test now tampers origin to loopback and asserts rejection without network access. Redirect/DNS/egress hardening remains open.

Next external action: user-authorized commit/push of these local fixes, then workflow_dispatch on the resulting SHA (or open/update PR). This task does not authorize another commit/push. Do not rerun baseline CI expecting local fixes to be included. Mandatory PostgreSQL17/pgvector, Zoekt, Graphify and Ollama acceptance remains unverified. Kotlin metadata-aware exported ABI, compatibility and cross-repository consumer impact remain PARTIALLY IMPLEMENTED and are the next functional work; compiler isolation is also partial pending OS resource/egress enforcement and further adversarial tests.

## Authorized CI repair cycle — 2026-11-25

User authorized commits and pushes during this execution exclusively to `fix/jackson3-postgres-acceptance`. Full combined working tree including mirror-origin rejection validated with `KOTLIN_COMPILER="$PWD/target/kotlinc/bin/kotlinc" JAVA_HOME="$PWD/target/jdk21/Contents/Home" PATH="$PWD/target/jdk21/Contents/Home/bin:$PATH" mvn test`: 150 passed, 0 failures, 0 errors, 0 skipped (36.278s). `git diff --check` passed. Commit these fixes, push only that branch, dispatch CI for the new SHA and append actual results below.

### Job-attempt state fencing

Current local follow-up guards completion, retry, contention requeue and disabled-repository failure by both RUNNING state and the claimed attempt number. A stale worker can no longer mark a later attempt failed/successful. No-op already-current success now holds the same transactional lease fence as generation publication. Shared PostgreSQL acceptance adds a real job row at attempt 2, rejects attempt-1 success/failure, completes attempt 2, and rejects late failure after success. This integration assertion is compiled only until its pushed CI run executes. Full Java21 suite after the initial state-guard changes: see subsequent validation entry; no integration success inferred.

CI repair commit `7a0313a823b24ca3abb984bb6490f0f757cd8ec5` was pushed and verified with `git ls-remote`. Dispatched https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45559395038 . Unit stage and strict no-skip report gate passed; run reached real Zoekt setup and Ollama model download. Follow-up job-attempt fencing commit `ae184d5c671cf8d2a56be87e001178c5609cab5f` was pushed/remote-verified and dispatched at https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45559648474 . Full Java21 unit suite on the initial attempt-fencing changes: 150 passed, zero failures/errors/skips (39.424s); subsequent disabled-job guard compiled successfully. Integration assertion still unexecuted as of this checkpoint. Final conclusions will be appended after actual run completion; do not infer service acceptance from setup progress.

### Actual PostgreSQL CI evidence and startup repair

Run 45559395038 on `7a0313a823b24ca3abb984bb6490f0f757cd8ec5`: unit 150 passed, zero failures/errors/skips. Real Zoekt, Ollama embedding dimension preflight, and Graphify installation succeeded. PostgreSQL suite executed 4 tests: **1 passed, 0 failures, 3 errors, 0 skips**. `GenerationRetentionPostgresIT` passed its real PostgreSQL V1–V10 cascade/outbox retry/transactional fencing tests. Shared acceptance and Graphify failed application startup due to an actual circular dependency: FlywayMigrationStrategy `databaseRoleGuard` injected Boot's JdbcTemplate, which depends on Flyway initialization. Fixed by obtaining a standalone JdbcTemplate from Flyway's own configured DataSource inside the strategy; production non-superuser rejection remains before migrations. Least-privilege test successfully applied all ten migrations as the restricted role but failed cleanup with SQLSTATE 2BP01 (privileges on database code_context_test); cleanup now drops grants owned by the unique temporary test role before dropping that role.

Independently, persisted contract-link creation now requires at least one shared team repository grant and both repositories enabled. Pruning removes links after shared access is revoked. Retention PostgreSQL fixture adds cross-team negative, explicit shared-access positive and revocation checks. Full Java21 unit suite on combined startup/cleanup/team-boundary repairs: 150 passed, zero failures/errors/skips (38.528s); PostgreSQL assertions require next CI execution.

### Kotlin exported ABI implementation (in progress)

Added official `kotlin-metadata-jvm` dependency at Boot-managed Kotlin 2.2.21. `KotlinAbiSurface` reads classfile annotations/methods without loading or initializing indexed classes, decodes Kotlin metadata visibility and JVM descriptors, and excludes internal/private members from source exports. Binary keys include owner, JVM name, parameter types and return descriptor; constructors/accessors/extensions/top-level functions retain their actual JVM identities. Binary removals and source visibility reductions are separate; source compatibility remains explicitly unknown. `KotlinAbiContracts` merges exported descriptor keys into the owning Maven module contract, updates deterministic contract/index hashes, and persists `kotlinJvmAbi`, metadata completeness, limitations, and UNKNOWN Kotlin source compatibility. Indexing now passes compiler-derived surfaces from semantic analysis to contract persistence. Failed compilation produces incomplete ABI metadata and UNKNOWN compatibility. Generator/rules versions bumped to 1.4.0/1.5.0 to invalidate old conclusions.

Real compiler metadata test covers internal visibility, getters, constructors, extensions, return descriptor changes, private visibility reductions and preserved overloads; it also exercises contract enrichment/hash and compatibility classification. Added persisted ABI evidence assertion in the shared PostgreSQL acceptance fixture. Cross-repository Kotlin method-impact routing still needs additional resolver adaptation and dedicated multi-repository/MCP scenarios; this is not complete Kotlin impact intelligence.

Run 45560138139 on 3f01bbbbf51fb22f290c7b584a3110f01c97c382: 150 unit tests passed; PostgreSQL suite **2 passed, 0 failures, 2 errors, 0 skips**. Least-privilege V1–V10 test and retention/fencing/team-grant test pass. Graphify/shared application contexts fail on ZoektSearchService constructor selection (two constructors without an injection annotation). Production constructor now explicitly annotated; a lightweight Spring container wiring regression test was added. No assertions weakened.

Kotlin ABI integration validation: combined Java21 suite 152 passed, 0 failures/errors/skips (41.843s). Subsequent conservative handling of unknown generated method visibility passed all six actual Kotlin compiler tests (32.572s). Unknown public metadata mappings now mark the ABI incomplete instead of claiming a complete export list. The source-visibility regression exercises metadata-aware export, exact JVM descriptor removal, Maven contract enrichment, stable hash change, and deterministic breaking binary comparison. PostgreSQL/MCP cross-repository Kotlin impact remains pending; do not label that requirement E2E verified. Zoekt wiring repair commit 1633ac8 was pushed and dispatched at https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45560731486 .

### Real Zoekt branch failure repair

Run https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45560731486 on 1633ac8: unit 151 passed (including Spring wiring regression), PostgreSQL suite **3 passed, 1 failure, 0 errors/skips**. Graphify actual CLI ingestion/persistence, least-privilege role and retention/fencing/team tests passed. Shared fixture reached real indexing and failed in Zoekt: `getCommit("refs/heads/", "HEAD"): reference not found`. The preceding unknown-local-host URL warning is nonfatal (confirmed in pinned Zoekt gitindex source); it was not the cause. Production invocation now passes repository.defaultBranch instead of HEAD to `-branches`. Added subprocess argument/metadata regression: configured `release/v1` is passed unchanged and generation-specific identity persists. Six focused Zoekt unit tests passed under Java21 (6.754s), zero failures/errors/skips. Graphify integration context now closes after its class to stop cached scheduled workers from competing for subsequent shared-fixture jobs. Fixture compiles; actual cleanup occurs on next run.

Kotlin ABI commit 7d4abeb48ddd13f0f947b9202d151ce3e186980c pushed/remote-verified; dispatched https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45560976845 . It has reached integration execution, but includes the pre-repair Zoekt branch command. Do not rerun that commit expecting the local branch fix.

Run https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45561496297 on b38f999fdfad94339b1d65c7916758c4d17e2119 still fails shared indexing; the full nested Zoekt log now reports `getCommit("refs/heads/", "main"): reference not found`. Three other ITs pass. Therefore configured-branch selection did not establish interoperability with the JGit mirror. Zoekt now receives the exact JGit-resolved HEAD commit SHA rather than asking go-git to resolve a branch ref. This selects the immutable revision indexed by the pipeline, avoiding branch-name resolution. A real Git fixture verifies the SHA argument and generation metadata; focused Java21 test passed (1 test, 0 failures/errors/skips, 8.927s). Real Zoekt verification still requires the next run; no local binary equivalence is claimed.

Run https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45562196839 on 5c5af19d4773edddda49f5baa899f367444d9f73 still fails shared acceptance: Zoekt go-git cannot resolve even the explicit commit SHA. Selector changes have not solved the underlying interoperability problem; no acceptance success claimed. Bounded failure diagnostics now include JGit ref database implementation, up to ten branch refs/SHA values, repository format, Git directory, and native `git cat-file -t` result, excluding config credentials and full environment. Focused Zoekt Java21 test passed (1, 0 failures/errors/skips). Building the pinned Zoekt source locally under ignored target directories to compare native Git/JGit/go-git on an identical generated fixture; network/compiler dependencies still resolving as of this entry.

### Confirmed Zoekt root cause and native integration proof

Local pinned-source reproduction revealed the underlying go-git error hidden by ResolveRevision: JGit clone packs use a filename derived from object IDs, which differs from the pack-content checksum. The pinned go-git v5.19.1 reader enforces equality between the pack filename and the pack checksum in the index; JGit's valid pack violates that naming assumption. Example local fixture: filename 3752a10377f86771f4a66bf60222b3706efef717, index pack checksum b0841a235fd48409e74e157089465fa6b70ddf08. Native `git verify-pack` reports valid; JGit and native Git read the commit; go-git reports object not found, subsequently masked as reference not found. Linux diagnostic run https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45563126949 on 9e4b0b6 corroborates valid JGit refs and native `cat-file` returning commit/exit0 while Zoekt fails.

`GitPackCompatibility` detects mismatched names by bounded trailer reads and invokes native Git repack only when necessary. Uses existing bounded ProcessRunner, 120-second limit, one pack thread, 64MiB window budget, disabled hooks and no inherited application environment. It preserves source/revision and checks compatibility afterward. Repacking can rewrite repository pack storage when new JGit packs appear; benchmark large-repository cost before broad rollout. Standard native repack semantics are used rather than hand-renaming live pack/index files.

Validation: actual pinned Zoekt was built under ignored target directories. Production `ZoektIndexer.index` against a real newly cloned JGit fixture successfully produced a .zoekt shard after normalization (`PRODUCTION_ZOEKT_INDEXER_PASSED`). Focused JGit/native-Git tests verify canonical-name normalization, unchanged commit/content and idempotent no-op (2 passed). Complete Java21 suite on final combined tree: **154 passed, 0 failures, 0 errors, 0 skipped**, 59.491s. This proves native indexing locally; PostgreSQL, HTTP search/MCP and full shared acceptance still require the next CI run. Native build/probe sources and caches are development artifacts under ignored target, not runtime dependencies.

### Real HTTP search envelope and query containment

Native pinned Zoekt server was built and run on loopback from the production Java indexer's real JGit fixture. Actual POST `/api/search` response has `Result.Files`, not root Files. Production search now validates/unwraps the Result object; shared-generation visibility helper reads the real envelope and waits up to 15 seconds for asynchronous shard watcher publication/removal (same positive/negative assertions retained). Actual `ZoektSearchService.search` calls against the native server passed exact `Money` and regex `convert\(` queries with file, line and commit evidence. This is real native HTTP integration with a fixture repository store; it is not PostgreSQL/MCP E2E verification.

Confirmed query containment defect using the pinned Zoekt parser: input `Money) repo:foreign or (Money`, after wrapping/appending the allowed repo, parses as OR of a foreign-repo branch and an allowed-repo branch. Added balanced-group/quote/trailing-escape validation so user syntax cannot break the authorization wrapper. Escaped and quoted regex parentheses remain supported. Actual production service rejects the escape input, and adversarial unit tests cover it. Full Java21 suite on the final envelope/containment/watcher tree: **156 passed, 0 failures/errors/skips**, 45.730s; `git diff --check` passed.

Run https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45567118043 on a2b9d502faa74bfe8117c0aac5400f9ce81753de confirms pack normalization advanced real indexing: shared acceptance reaches provider-v1 visibility assertion, which fails because the old helper reads root Files. Other 3 PostgreSQL integration tests pass. No shared/MCP gate success claimed. Next run must include the response-envelope fix; prior run does not contain it.

### Cross-team authenticated MCP regression coverage

Added separate initialized MCP HTTP session authenticated as an isolated team with no repository grants. All six actual tools are requested: search/find must return no repository rows, read/neighbors/semantic/impact must return an MCP/RPC error, and source content must not leak. Each request carries that team's API key, session ID and protocol headers; the original team's exact positive assertions remain. Semantic search now authorizes a specified repository before invoking the embedding model. Focused regression proves denied repository operations do not touch the absent model/JDBC (1 pass). Combined Java21 suite **157 passed, 0 failures/errors/skips**, 48.259s. Added HTTP isolation assertions compile but require the next pushed acceptance run; not yet E2E verified.

Zoekt envelope/query-scope commit e29312641f30aedcea51ca3095c917f4d4b6a2ea pushed/remote-verified and dispatched at https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45567917112 . As of this entry, unit gate passed and service preparation is in progress. No service-success conclusion inferred.

Run https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45567917112 on e29312641f30aedcea51ca3095c917f4d4b6a2ea advances past real Zoekt provider-v1 search and into persisted REST/Kafka checks. Shared acceptance errors on PostgreSQL SQLSTATE 42702: unqualified `min(confidence)` is ambiguous between contracts and contract_links. Test query now explicitly checks `min(l.confidence)>0`; count(*)=2 and all exact link identity assertions remain unchanged. Java21 test compilation passes; database execution requires next CI. Three other integration tests pass. Cross-team MCP commit c2f5208bfdc9fa313a80eb474916c2197dde595f was pushed/remote-verified and dispatched at https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45568392845 ; it predates the SQL qualification repair.

### Multi-repo MCP search assertion and idempotent Zoekt cleanup — 2026-11-25 resume

Working tree was clean at pushed `69ea231f0ddc7e42208347dbbe1ef7e5e0497262` (no uncommitted Codex changes to commit). Resumed from that SHA.

Run https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45568564540 on 69ea231: unit gate passed; PostgreSQL suite **3 passed, 1 failure, 0 errors/skips**. Graphify, least-privilege V1–V10, and retention/fencing passed. Shared acceptance advanced through real Zoekt indexing, provider v1/v2 visibility, REST/Kafka link SQL (after qualification), and authenticated MCP search. Failure: `assertSearchResponse` inspected only `results.get(0).matches.Files` (common-library Money.java) while ConsumerA.java line evidence was present in a later authorized repository entry. MCP payload already contained ConsumerA.java with LineMatches; the helper path was wrong. Fixed by scanning all result entries for ConsumerA.java without weakening line/commit assertions.

Separately, Zoekt obsolete-generation removal treated already-absent shards (`repository "…" not found`) as hard failures during incremental consumer republish. `ZoektIndexer.removeGeneration` now treats not-found as successful cleanup for durable retries; unit regression covers exit-1 not-found. Full Java21 suite on combined tree: **158 passed, 0 failures/errors/skips**, 42.503s.

### MCP neighbor SQL and next acceptance advance — 2026-11-25

Pushed `59975dab6d3261acd6021c0cfe1eea30583b38da`; remote SHA verified. Dispatched https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45574858562 .

Result: unit gate passed; PostgreSQL suite **3 passed, 1 error, 0 failures/skips**. Graphify, least-privilege, retention/fencing pass. Shared acceptance advanced past multi-repo MCP exact/regex search (prior assertion fix verified). Failure at `find_symbol`/`get_neighbors` MCP path: tool text began with `PreparedStatementCallback` (Spring JDBC) and was not JSON. Root cause in `get_neighbors`: PostgreSQL rejects `'SEMANTIC_'+edge.resolution_status` (`text + text`); corrected to `'SEMANTIC_'||…`. `find_symbol` hardened (required symbol, `coalesce` on nullable qualified names, explicit `published=true`, positional `ORDER BY`). Acceptance helper now fails with raw MCP error text instead of a Jackson parse exception, and asserts published ConsumerA `code_nodes` before the tool call.

Concurrent functional/security work included in the same push after local validation: Kotlin ABI binary→semantic impact routing (`KotlinAbiSurface.removedSemanticKeys`), CrossRepoImpactResolver Kotlin provenance/completeness, ConsumerA Kotlin→Java fixture + MCP impact assertions (expect 2 semantic impacts), SSRF DNS/private-address guards for Git/Maven, path containment, rate-limit gate, audit redaction. Full Java21 suite: **189 passed, 0 failures/errors/skips**, 48.398s. Service acceptance still requires the next CI run; do not claim E2E success from unit evidence.

### GitRepositoryManager Spring wiring repair — 2026-11-25

Run https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45584498033 on e2f19af: unit gate passed; PostgreSQL suite **2 passed, 0 failures, 2 errors, 0 skips** (least-privilege + retention). Shared and Graphify failed ApplicationContext load: `GitRepositoryManager` gained a package-visible test constructor for DNS injection, and Spring selected that greediest constructor then failed with `No default constructor found`. Production single-arg constructor now marked `@Autowired`; Spring wiring regression added. Contract intelligence increment (OpenAPI schema support, structured REST/Kafka compatibility, ambiguous relationship refresher hooks) included after local validation. Full Java21 suite: **199 passed, 0 failures/errors/skips**.

Run https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45586396558 on de20325: unit gate passed; PostgreSQL suite **3 passed, 1 failure, 0 errors/skips**. Graphify, least-privilege, retention pass; ApplicationContext loads. Shared acceptance fails at Kotlin ConsumerAKotlin call-site line assertion: helper assumed every `convert(String)` call is on line 5 (Java fixture), but the single-line Kotlin caller is on line 2. Assertion is now path-aware (Java line 5, Kotlin line 2) without weakening resolution requirements.


### Contract intelligence slice (CONTRACT-1..4) — 2026-11-25

Local uncommitted continuation on top of `de20325` ledger update only (no further commit/push from this agent).

Implemented against extractor/compatibility engine:
- OpenAPI JSON `$ref` resolution into durable `restOp{params;request;response}` signatures with required/optional evidence (`OpenApiSchemaSupport`, REST extractor 1.1.0).
- Direction-aware REST compatibility: required request add / response remove = BREAKING; optional response add / request remove = BACKWARD_COMPATIBLE; unstructured OpenAPI remains UNKNOWN (`StructuredSchemaCompat`).
- Kafka Avro/JSON Schema → `fields{}` signatures; proto message signatures reuse field-number/type rules; optional field add not breaking; config-derived topic placeholders covered in unit tests.
- `ConsumerContractIndexRefresher.reevaluateAmbiguousRelationships` demotes multi-provider links and restores RESOLVED when one provider remains; GraphSymbolBinder rebind unit covers AMBIGUOUS→RESOLVED.
- Shared IT strengthened: REST/Kafka link evidence must retain provider/consumer file+commit.

Versions: generator `1.5.0`, compatibility rules `1.6.0`. Validated: **199** Java21 unit tests passed, 0 failures/errors/skips. Not done: YAML OpenAPI full `$ref` (no YAML mapper), multi-repo persisted OpenAPI/Avro evolution + MCP consumer-impact fixtures, PG proof of ambiguity demotion under contention, late-provider CI execution.

Run https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45588085639 on d0912f2: unit gate passed; PostgreSQL suite **3 passed, 1 failure, 0 errors/skips**. Shared acceptance advanced through Kotlin call-site lines, all six MCP tools, and dual Java/Kotlin semantic impacts. Failure: impactCandidates serialize RepoRef as nested owner/name with Fixture casing, while the assertion looked for lowercase `fixture/consumer-service-a`. Assertion now checks `"name":"consumer-service-a"` / `consumer-service-b` together with the REST/Kafka contract keys. Consumer A/B remain present in the payload; no assertion weakened beyond format alignment.


### Mandatory CI stages green — 2026-11-25

Remote SHA verified: `a176f6e9a740fd966b4343f9b076f430d5627cd7`.
Workflow: https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45590165866 — **success**.

| Gate | Result |
|---|---|
| Unit + no-skip | **199 passed, 0 failures, 0 errors, 0 skipped** (Java Temurin 21.0.13) |
| SharedLibraryImpactPostgresIT | **1 passed, 0/0/0** (69.13s) — real Zoekt/Ollama/Graphify/MCP path |
| GraphifyPostgresIT | **1 passed, 0/0/0** |
| GenerationRetentionPostgresIT | **1 passed, 0/0/0** |
| ProductionDatabaseLeastPrivilegeIT | **1 passed, 0/0/0** (V1–V10 as restricted role) |
| All four verify-* report gates | success |

PostgreSQL service: `pgvector/pgvector:0.8.1-pg17`. Overall production decision remains **NO-GO** until remaining ledger items (hard purge, OS compiler sandbox, full ops drills, remaining contract/security depth) are closed; mandatory service-backed acceptance for this slice is now executed and green.

### Kotlin provider multi-repo + hard purge slice — 2026-11-25

Checkpoint before this slice: HEAD `7199b14` = remote; `a176f6e..7199b14` docs-only; green CI https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45590165866.

Implemented locally (pending push/CI):
- Kotlin provider fixture `kotlin-common-library` with K→K, J→K consumers (pinned 1.0) and upgraded 2.0 consumer excluded; ABI baseline→head removals; MCP expects 3 impacts; CI gate requires 2 SharedLibraryImpactPostgresIT cases.
- Authorized hard purge (`DELETE ...?purge=true`), mirror deletion, Zoekt reconcile helpers, permanent-failure job unit test, GenerationRetentionPostgresIT purge/reclaim assertions.

Local Java21 suite: **207 passed, 0 failures/errors/skips**. PG Kotlin-provider IT and purge PG assertions require next CI dispatch.

### CI repair + contract/security/ops continuation — 2026-11-25 evening

Failed run https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45604442981 on `5eda7e6`: unit gate green; PostgreSQL suite **3 passed, 2 failures**. Root causes:
1. Kotlin MCP impact assertion used substring `common-library`, which matches `kotlin-common-library`. Tightened to exact Java-fixture identities.
2. Hard-purge retention assertions created doomed/contended generations inside the top-2 successful protection window. Test now inserts newer successful protectors so those generations are eligible.

Independent continuation (unit-validated before push):
- OpenAPI YAML `$ref` resolution via SnakeYAML → same `OpenApiSchemaSupport` path as JSON (`RestContractExtractor` 1.2.0); generator `1.6.0`.
- Dual-key API rotation `team=current|previous` (`ApiKeyRoster` + filter + production strength checks); secrets never appear in roster `toString`.
- Kafka Avro required-field addition treated as BREAKING (`compareKafkaFields`); rules `1.7.0`.
- Prometheus alert rules under `deploy/prometheus/alerts.yaml`; backup/restore smoke scripts under `scripts/`.

Next: push, workflow_dispatch, confirm Kotlin-provider + purge PG gates, then continue remaining ledger items (domain Micrometer meters, ambiguity PG contention, incremental rename, OS compiler sandbox, DR drill).

### Mandatory CI green for Kotlin provider + hard purge — 2026-11-25

Remote HEAD `b2346ee049528c705991de75051ef960da4abb3a`.
Workflow: https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45629807050 — **success**.

| Gate | Result |
|---|---|
| Unit + no-skip | **216 passed, 0 failures, 0 errors, 0 skipped** |
| SharedLibraryImpactPostgresIT | **2 passed** (Java Money + Kotlin Amount provider / MCP) |
| GraphifyPostgresIT | **1 passed** |
| GenerationRetentionPostgresIT | **1 passed** (fencing, hard purge, orphan reconcile) |
| ProductionDatabaseLeastPrivilegeIT | **1 passed** |

Preceding CI failures on `5eda7e6`/`0345a18` were repaired by:
1. Exact Java-fixture identity checks (avoid `kotlin-common-library` substring collision).
2. Retention top-2 protectors for doomed/contended generations.
3. Persist assertion scoped to `semanticMethodImpacts` (upgraded consumers may appear in graph/candidate metadata).
4. Retention `collect` uses `SET LOCAL lock_timeout='250ms'` so a fenced lease returns false instead of blocking.

Domain Micrometer meters (`PlatformMetrics`) and prior contract/security/ops slice are included in this green SHA lineage (`0345a18`→`4ca099f`→`b2346ee`). Overall decision remains **NO-GO** pending remaining ledger depth (OS compiler sandbox, DR drill, rename/dependency incremental PG cases, multi-replica rate limit, cluster smoke).


### Semantic structural coverage — 2026-11-26 (generator 1.18.0)

The pinned Graphify **0.9.118** wheel was inspected directly (SHA-256 `f572453c275e82dcc052f6cf7fa79c0a5a14eaefab08ac9112e31edf7b9d75aa`). Its dispatch has **56 extractor families**, including configuration, documents, forms, and project files; this is not a count of programming languages or proof of structural semantic support. Installed local Graphify 0.8.18 was not used as evidence for the pinned release. Graphify nodes can carry only a start location, so semantic definitions use complete parser ranges instead of guessing ends from the next declaration.

| Meaning-index source family | Structural implementation | Current evidence |
|---|---|---|
| Java | JavaParser raw source class headers, methods/constructors, same-scope tiny merging | Existing source tests, real provider acceptance; latest full release gate pending |
| Kotlin | Portable Tree-sitter JNI core0.26.6 with pinned Kotlin grammar0.3.8.1; complete definition/header ranges, shared tiny-group planner | Native grammar and Unicode tests; real Kotlin provider gate pending new epoch |
| Python | Same native parser pipeline, exact method/header ranges and source gaps | Native fixture; real Ollama/PG/MCP natural-language refund fixture added, execution pending |
| JavaScript, TypeScript, TSX | Same pipeline, grammar-specific class/method/function nodes | Three native grammar fixtures; broader declaration and framework syntax remains open |
| Go, Rust, C, C++, C#, Ruby | Same pipeline with native complete callable ranges | Six native grammar fixtures; full language feature matrices and service acceptance remain open |
| Markdown, YAML, JSON | Existing parser section boundaries and bounded complete-input sizing | Existing unit/service coverage; arbitrary schema semantics are separate |
| Other pinned Graphify dispatch families | Documented 60-line/10-overlap fallback, subject to character and token limits | **PARTIALLY_IMPLEMENTED** relative to full promised structural coverage; do not advertise parser parity from Graphify graph support |

Imports, intervening state and trailing source are retained as separate source fragments. Unknown grammar, malformed syntax or unavailable native library uses fallback rather than fabricated structural ranges. Parsers/trees for structural chunking are per-file, closed and limited to two seconds and 256 traversal levels. Source offsets use the JNI binding's actual UTF-16 character indices; precomputed physical line starts avoid rescanning the file prefix per declaration.

Native runtime correction: the former seart1.12 artifact was incompatible with both the ARM Mac JVM (Intel-only dylib) and glibc Linux (its .so links libc.musl-x86_64.so.1). The earlier Intel/Rosetta332-test result is historical evidence, not Linux acceptance. Replaced that dependency with platform-specific `io.github.bonede` JNI core0.26.6 and explicitly pinned grammar artifacts in pom.xml. Published grammar jars contain ARM/Intel macOS and GNU Linux binaries. The extracted GNU core requires GLIBC symbols no newer than2.14, compatible with the configured Noble image; actual Linux loading remains a strict CI assertion. No libc shim, system runtime change, native-test skip or architecture-specific bypass was added.

The small NativeSyntax adapter owns/closes parser and tree lifetimes, feeds canonical UTF-8 and maps native byte positions to exact Java character offsets (ASCII identity fast path, precomputed map otherwise) and preserves a two-second parse deadline using the binding's native progress callback plus bounded input-reader checks. Cancellation preserves worker interruption; an aborted parser resets and is reusable. These are actual native tests, not mocks. Primary binding API/platform reference: https://github.com/bonede/tree-sitter-ng. The current local validation uses the normal ARM Java21 runtime; Rosetta is no longer required.

Additional structural grammar coverage: Scala, PHP, Swift, Lua and Bash and pinned-extractor JS/TS/C++/Ruby extension aliases. Eighteen structural cases plus mandatory native Kotlin, existing overload and two native cancellation/reuse cases all pass on ARM Java21 (22 focused cases). Lua's actual bundled AST node is function_definition_statement; this was established from its valid tree rather than inferred from another grammar's selector. All full-service and remaining-family limitations above still apply.


#### Lua runtime and local semantic verification

The bonede2.1.3a GNU Lua grammar itself is defective: direct UTF-8 parsing of a valid unchanged fixture fails under multiple core versions. It is replaced by checksum-verified pure grammar libraries from upstream v0.4.1, exact commit816840c592ab973500ae9750763c707b447e7fef. Source attribution, full MIT license, all four architecture hashes and rebuild instructions are in src/main/resources/native/lua-v0.4.1/. The checked-in rebuild script was executed. No runtime compiler, libc shim, parser-test skip or permissive selector is used.

Twenty-two actual native tests pass on each Mac ARM/Intel and GNU ARM/x86 runtime. The integrated Graphify/Ollama/pgvector/HTTP MCP case also passes locally with Java/Kotlin/Python/TypeScript method chunks, tiny grouping, large Unicode input bounds, model/vector provenance, changed-input-only embedding and deletion visibility. Full default Java21 suite339 passed. Other language/scale/release requirements remain separately tracked; full exact-SHA CI still must pass.
