# Production readiness ledger

**Authoritative** production-readiness progress for Code Context Platform.  
Updated continuously. Do not mark `VERIFIED_PRODUCTION_READY` without executable evidence.

| Field | Value |
|---|---|
| Branch | `fix/jackson3-postgres-acceptance` |
| Local/remote HEAD (start of this closure) | `9bd5059007e302a409cc8f413026b6844df7efd8` |
| Last green mandatory CI SHA | `5c835622803087a7445ed1693dd8dc1b00483c88` |
| Green CI URL | https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45676761869 |
| Prior failed CI | a374923/08c449c run 45636811199 — dual-ctor wiring; fixed in ab3dcbb |
| Overall decision | **NOT READY FOR PRODUCTION** |
| Working progress companion | `docs/IMPLEMENTATION_PROGRESS.md` |
| Reference repo (read-only) | `WynkLimited/MultiRepo-Code-Review-Agent` @ `main` / `65144c6060e97c491546674586ea8ae5020588c7` |

Status vocabulary (this ledger):  
`NOT_IMPLEMENTED` · `PARTIALLY_IMPLEMENTED` · `IMPLEMENTED_UNVERIFIED` · `TEST_FAILED` · `BLOCKED` · `VERIFIED_PRODUCTION_READY` · `NOT_APPLICABLE_WITH_JUSTIFICATION`

---

## Resume checkpoint

**Session progress (this closure):** Ledger + MultiRepo parity; proposed-change MCP; independence gate; webhook freshness+PG dedupe; nested+cross-file OpenAPI; Avro/OpenAPI MCP evolution; hop incompleteness flags; admin key status; REST ambiguity PG; subprocess env scrub; mandatory CI green @ `f0a36ad`.

**Overall:** **NOT READY FOR PRODUCTION** — external blockers remain (OS sandbox, DR/PITR, k8s, Gradle, representative perf, multi-JVM crash drills).

**Next executable actions (priority):**

1. Method-chain multi-hop beyond re-export candidates (XRI-HOP) — incompleteness flags already exposed.
2. Full-application multi-instance crash/heartbeat drills (IDX-1/2) beyond the verified separate-JVM lease/publication workers.
3. External blockers: OS sandbox, DR/PITR drill, k8s smoke, Gradle (XRI-GRADLE), representative perf.

---

## Requirement register

### Context foundations

| ID | Category | Requirement | Status | Implementation | Evidence / next action |
|---|---|---|---|---|---|
| CTX-1 | Text index | Zoekt exact/regex search with evidence | VERIFIED_PRODUCTION_READY | `ZoektSearchService`, `ZoektIndexer` | CI b2346ee SharedLibrary IT |
| CTX-2 | Code graph | Graphify → PostgreSQL nodes/edges | VERIFIED_PRODUCTION_READY | `GraphifyIndexer`, `GraphifyPostgresIT` | CI b2346ee |
| CTX-3 | Contract/dependency index | REST/Kafka/Maven/proto contracts + links | PARTIALLY_IMPLEMENTED | extractors + shared IT links | Nested+cross-file OpenAPI `$ref` (1.3.0); Avro MCP evolution @ f905835 |
| CTX-4 | Semantic index | Ollama embeddings + pgvector MCP | VERIFIED_PRODUCTION_READY | indexing chunks + `semantic_search` | CI a176f6e/b2346ee real Ollama |

### MCP tools

| ID | Requirement | Status | Evidence |
|---|---|---|---|
| MCP-1 | `search_code` | VERIFIED_PRODUCTION_READY | SharedLibraryImpactPostgresIT Streamable HTTP |
| MCP-2 | `read_file` | VERIFIED_PRODUCTION_READY | Same + ACL denial |
| MCP-3 | `find_symbol` | VERIFIED_PRODUCTION_READY | Same |
| MCP-4 | `get_neighbors` | VERIFIED_PRODUCTION_READY | Same |
| MCP-5 | `semantic_search` | VERIFIED_PRODUCTION_READY | Same + Ollama |
| MCP-6 | `impact_analysis` | PARTIALLY_IMPLEMENTED | Java+Kotlin provider E2E green; proposed-change inputs / multi-hop incomplete |

### Repository lifecycle

| ID | Requirement | Status | Next |
|---|---|---|---|
| LIFE-1 | Onboard / mirror / index | VERIFIED_PRODUCTION_READY | CI shared fixture |
| LIFE-2 | Soft disable / re-onboard | VERIFIED_PRODUCTION_READY | GenerationRetentionPostgresIT |
| LIFE-3 | Authorized hard purge | VERIFIED_PRODUCTION_READY | CI b2346ee purge+reclaim |
| LIFE-4 | Cross-team re-onboard ACL depth | PARTIALLY_IMPLEMENTED | Expand ACL adversarial cases |

### Indexing reliability

| ID | Requirement | Status | Next |
|---|---|---|---|
| IDX-1 | Lease fencing / publication | PARTIALLY_IMPLEMENTED | GenerationRetentionPostgresIT lease expiry + fence; separate-JVM lease/crash/fencing CI green at bee5d60; full-app concurrency still open |
| IDX-2 | Durable jobs / permanent failure | PARTIALLY_IMPLEMENTED | Shared IT failure-boundary; expand stale-worker cases |
| IDX-3 | Incremental ≡ full rebuild | PARTIALLY_IMPLEMENTED | Rename+dependency markers added to shared IT; await next CI |
| IDX-4 | Zoekt orphan reconcile | VERIFIED_PRODUCTION_READY | Retention PG + unit |

### Cross-repository intelligence

| ID | Requirement | Status | Next |
|---|---|---|---|
| XRI-JAVA | Java→Java shared-lib impact | VERIFIED_PRODUCTION_READY | CI SharedLibrary IT |
| XRI-J2K | Java→Kotlin | PARTIALLY_IMPLEMENTED | Mixed fixture present; deepen |
| XRI-K2J | Kotlin→Java | VERIFIED_PRODUCTION_READY | CI Kotlin provider IT |
| XRI-K2K | Kotlin→Kotlin | VERIFIED_PRODUCTION_READY | CI Kotlin provider IT |
| XRI-VER | Version-pinned vs upgraded consumers | VERIFIED_PRODUCTION_READY | CI excludes upgraded consumer from semantic impacts |
| XRI-PROP | Proposed-change classification inputs | VERIFIED_PRODUCTION_READY | Optional @ToolParam; schema + MCP proposedChange through Streamable HTTP @ 19aa954 |
| XRI-HOP | Multi-hop / indirect callers | PARTIALLY_IMPLEMENTED | Re-export contract hops ≤3; bounded published intra-repository callers verified in ae417c1 CI; arbitrary cross-repository method propagation remains open |
| XRI-GRADLE | Gradle dependency resolution | PARTIALLY_IMPLEMENTED | Explicit unavailable coverage gap recorded; resolver still NOT_IMPLEMENTED |
| REF-IND | No MultiRepo coupling | VERIFIED_PRODUCTION_READY | `ArchitecturalIndependenceTest` unit gate |
| REF-PARITY | Functional parity matrix | PARTIALLY_IMPLEMENTED | See `docs/CROSS_REPO_IMPACT_PARITY_MATRIX.md` |
| CONTRACT-4 | Ambiguous relationship refresh | VERIFIED_PRODUCTION_READY | Dual REST providers → AMBIGUOUS; disable → clean relink @ 440a4ef |

### Security

| ID | Requirement | Status | Next |
|---|---|---|---|
| SEC-AUTH | API keys + dual-key rotation | PARTIALLY_IMPLEMENTED | Dual-key + admin status; PostgreSQL shared team limiter and real restricted-role contention assertions verified in 5c83562 CI |
| SEC-ACL | Cross-team tool isolation | VERIFIED_PRODUCTION_READY | Shared IT all six tools |
| SEC-SSRF | Git/Maven URL guards | PARTIALLY_IMPLEMENTED | Loopback/metadata Maven host rejection unit green; HTTP redirect residual needs NetworkPolicy |
| SEC-SANDBOX | OS compiler sandbox | PARTIALLY_IMPLEMENTED | Env scrub + kotlinc allowlist; cgroup/seccomp BLOCKED pending OS |
| SEC-WH | Webhook replay + timestamp freshness | VERIFIED_PRODUCTION_READY | HMAC + delivery-id PG dedupe + head_commit age @ f0a36ad |

### Operations

| ID | Requirement | Status | Next |
|---|---|---|---|
| OPS-METRICS | Domain meters + alert YAML | PARTIALLY_IMPLEMENTED | Live scrape/alert fire needs ops env |
| OPS-BACKUP | Scripts + docs | PARTIALLY_IMPLEMENTED | PITR drill BLOCKED without isolated PG |
| OPS-K8S | Manifests | PARTIALLY_IMPLEMENTED | Cluster smoke BLOCKED |
| OPS-PERF | Pilot ≤1 min searchable | PARTIALLY_IMPLEMENTED | Representative benchmark open |

### Validation

| ID | Requirement | Status | Evidence |
|---|---|---|---|
| VALID-CI | Mandatory Java21 + PG ITs | VERIFIED_PRODUCTION_READY | 5c83562: 244 unit + seven PostgreSQL cases, including separate-JVM recovery |
| VALID-INDEP | Build without MultiRepo present | VERIFIED_PRODUCTION_READY | `ArchitecturalIndependenceTest` + pom has no MultiRepo deps |

---

## External blockers (continue other work)

| Blocker | Required operator action | Affected IDs |
|---|---|---|
| No local Docker daemon in prior sessions | Use CI workflow_dispatch | PG ITs |
| OS cgroup/seccomp sandbox | Platform/container policy | SEC-SANDBOX |
| Isolated PG for PITR | Provision restore environment | OPS-BACKUP |
| K8s staging cluster | Deploy smoke | OPS-K8S |
| Real Cursor/Claude/Windsurf clients | Client lab access | MCP client compatibility |
| Org security / pilot approval | External gate | Final release |

---

## Evidence log (recent)

| SHA | What | CI |
|---|---|---|
| `a176f6e` | Mandatory gates first green (199 unit + 4 PG) | 45590165866 success |
| `5eda7e6` | Kotlin provider + hard purge (failed IT asserts) | 45604442981 failure |
| `b2346ee` | Kotlin+purge CI green (216 unit, 2 shared IT) | 45629807050 success |
| `9bd5059` | Ledger evidence + rate-limit config | docs push |

---

## Final production decision (current)

**NOT READY FOR PRODUCTION**

Technical pilot slice (six MCP tools, Zoekt, Graphify, Ollama, Java+Kotlin provider impact, hard purge) is CI-verified. Remaining PARTIAL/BLOCKED items above prevent `VERIFIED_PRODUCTION_READY`.


## Codex recovery from local Cursor handover

Verified the existing checkout root, branch `fix/jackson3-postgres-acceptance`, clean tree and full SHA `c17b5e6c3792b803b85b4f18c3ceca6454fa07b0` before editing. Remote branch matched that SHA. Read `.agent-handover/GIT_CHECKPOINT.txt`, the three root Cursor JSONL transcripts, the compaction record and relevant subagent reports. The last Cursor tool record is an interruption immediately after the Gradle coverage-marker checkpoint; do not restart older CI failures already repaired in that history. All unresolved rows above remain open. Continue in this checkout without new clones, worktrees or branches.

Checkpoint evidence: [CI 45668813364](https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45668813364) succeeded on exactly c17b5e6. Downloaded Surefire reports confirm **233 unit tests + 6 PostgreSQL cases**, all passed with no failures/errors/skips (3 shared-library, 1 Graphify, 1 retention, 1 least-privilege). Unchanged local Java21 baseline passed 233 tests (39.009s). Initial sandboxed run had five Mockito self-attach initialization errors; the identical suite passed with required execution permissions. No tests were removed or skipped.

### XRI-GRADLE: coverage versus freshness

Status: **PARTIALLY_IMPLEMENTED**. Gradle dependency resolution remains NOT_IMPLEMENTED; the checkpoint records an explicit unavailable marker. Corrected `PostgresContractIndexStore.freshness`: that recognized repository coverage marker no longer makes a newly indexed snapshot perpetually STALE. `hasCoverageGaps` remains true. Actual extractor version drift, missing/unknown extractors, failed markers, old generator versions and invalid index hashes retain their stale/invalid behavior. Four regression tests in `ContractIndexFreshnessTest` cover those distinctions. Focused coverage/freshness suite: 6 passed. Next: implement safe Gradle dependency resolution separately; do not run untrusted build lifecycles to fill the gap.

### IDX-1/IDX-2: independent JVM failure-recovery fixture

Status: **IMPLEMENTED_UNVERIFIED** for this new integration fixture; the overall reliability requirements remain PARTIALLY_IMPLEMENTED. `RepositoryLeaseProcessPostgresIT` starts independent JVM workers using the production lease manager and a real isolated PostgreSQL schema. It checks heartbeat job timestamp renewal; busy rejection in a second JVM; expired owner rejection during a fenced publication transaction; preservation of the newer published generation; forced worker kill and subsequent expiry/recovery by another JVM. Production lease/heartbeat defaults remain five minutes/thirty seconds. Only the package-scoped test constructor uses three-second/two-hundred-millisecond timings. The production constructor has explicit Spring wiring. Worker logs exclude credentials passed through environment variables and are retained under `target/lease-process-logs`; CI uploads them and requires exactly one passed, unskipped process-recovery case.

Combined Java21 unit suite (new IT compiles but is not selected by default): **237 passed, 0 failures/errors/skips**, 41.349s. `git diff --check` and CI YAML parsing passed. This is not a claim that the process-recovery PostgreSQL test ran. The fixture tests separate lease/publication workers, not full application-instance indexing or OS/network fault partitions. Those broader requirements remain open.

Git publication blocker: automatic approval review rejected the attempted commit because it considers prior commit/push authorization superseded. The entire rejected command did not execute; no commit/push occurred. The user subsequently authorized publication with “push it”. Commit/push only this existing feature branch, dispatch CI for its exact SHA and record actual recovery-test results. The green c17 run predates these local changes.

## Autonomous continuation — 2026-11-26

User authorization now covers scoped validated commits, pushes exclusively to this branch, workflow dispatch and CI repair cycles. No force-push, merge or production deployment is authorized. This supersedes the historical publication blocker recorded above.

**Accepted checkpoint:** run https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45673489850 succeeded for exact SHA `bee5d607adbed433b549854a50a526861d9096d9`. Downloaded artifact XML records **237 unit + 7 PostgreSQL tests passed**, zero failures/errors/skips. PostgreSQL classes: SharedLibraryImpactPostgresIT 3; GraphifyPostgresIT 1; GenerationRetentionPostgresIT 1; ProductionDatabaseLeastPrivilegeIT 1; RepositoryLeaseProcessPostgresIT 1. The latter executed renewal, contender rejection, expired-owner publication fencing and forced process termination/recovery. This is separate-JVM lease/publication evidence, not full application-instance or network-partition certification. Artifacts retained under ignored `target/ci-bee5d60-reports`.

**XRI-HOP slice:** `PublishedCallerChainResolver` walks exact resolved V5 edges backwards from a directly impacted method inside the consumer's captured published generation and commit. Every query enforces enabled repository and authenticated team ACL. Bounded to three edges, 100 returned callers and 100 SQL queries; cycles terminate; budget/depth flags are explicit. Evidence includes ordered symbols, files/lines, generation, provider symbol, dependency/version, confidence and baseline compatibility. Caller reachability remains advisory and never asserts current/behavioural breakage. Existing direct impact list/assertions and `methodChainMultiHopSupported=false` remain intact because arbitrary cross-repository propagation is still unsupported.

Local Java21 full suite before the final additional signature fixture: **241 passed**, zero failures/errors/skips (48.725s); final focused resolver suite **5 passed**, including real JavaParser two-parameter canonical signatures. PostgreSQL/HTTP MCP fixture now requires exactly two upstream callers, exact paths/generation/commit and compatibility, and denies unauthorized teams and incorrect generations using production SQL. These new service assertions are **IMPLEMENTED_UNVERIFIED** until the next exact-SHA CI. Overall status stays NOT READY FOR PRODUCTION.

**Context reconciliation:** recovered the original master directive and Cursor checkpoint/transcripts. `CONSOLIDATED_REQUIREMENTS.md`, `CURSOR_TO_CODEX_HANDOVER.md`, `IMPLEMENTATION_GAP_ANALYSIS.md` and `EXECUTION_CHECKPOINT.md` were not found in this checkout (including hidden handover paths). Their contents cannot be claimed as reviewed. This ledger and the recovered master directive remain the available requirement sources. Resume next with CI verification for this caller-chain slice, then remaining actionable gaps; full application concurrency, Gradle, performance, OS containment, staging and DR remain open.

### Shared request-budget slice — 2026-11-26

SEC-AUTH remains **PARTIALLY_IMPLEMENTED** pending service evidence and the broader rollout security gates. Positive `RATE_LIMIT_PER_MINUTE` budgets now default to PostgreSQL via `RATE_LIMIT_STORE=postgres`, using one atomic UPSERT per initial authenticated request and database minute boundaries. V11 adds only team/window/counter data, with no key material; applied migrations V1–V10 were not rewritten. Shared state survives instance replacement. Explicit memory mode remains single-process only. Budget-store failure fails closed with generic HTTP 503; exhausted budget returns 429. No authentication, origin, CSRF or async-dispatch policy changed.

`RateLimitGateTest`: **6 passed** including existing 429 checks, safe 503, invalid backend rejection and disabled-store behaviour. Complete Java21 suite: **244 passed**, zero failures/errors/skips, 40.310s. YAML parsed and `git diff --check` passed. Added real PostgreSQL restricted-role contention checks to `ProductionDatabaseLeastPrivilegeIT`: two independent gates and 32 concurrent calls consume exactly the eleven remaining requests, counter saturation, per-team isolation, exhausted state after gate replacement, expired-window reset, and one row per team. The fixture pins a future minute to avoid crossing a real minute boundary during contention; production uses database time. Full application migration assertion now requires V11. These PostgreSQL changes are **IMPLEMENTED_UNVERIFIED** until exact-SHA CI completes.

Caller-chain CI currently running at https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45676005931 for `ae417c137622958072b15e39d1300dd2255ef885`. An accidentally duplicated dispatch (45676022642) was cancelled; no gate was bypassed on the retained run. Next: verify this run, publish the validated shared-budget slice and verify its mandatory workflow, then continue remaining actionable requirements. Logical backup/restore scripts exist but only have prior dry-run evidence; an isolated CI logical restore drill is an actionable step independent of the externally blocked production WAL/PITR/staging drills.

### Caller-chain CI evidence and logical restore drill — 2026-11-26

`ae417c137622958072b15e39d1300dd2255ef885` passed https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45676005931. Downloaded XML: **242 unit + seven PostgreSQL cases passed**, no failures/errors/skips. SharedLibraryImpactPostgresIT requires exact two-hop caller paths and unchanged direct Java/Kotlin impacts through authenticated HTTP MCP; PostgreSQL ACL/wrong-generation negative assertions executed. XRI-HOP remains PARTIALLY_IMPLEMENTED for arbitrary cross-repository method propagation. Shared-budget 5c83562 CI is running at 45676795836.

OPS-BACKUP: repaired logical script integrity/failure handling and added a mandatory disposable PostgreSQL 17 restore drill. Backups have private unique directories, checksum sidecars, explicit user/password environment support and failed-dump removal. Restore validates checksum before any database access, performs an atomic fail-fast restore and raises on missing tables/extensions/history. The old boolean SELECT could return false without failing; this defect is corrected. CI uses actual version-matched pg_dump/pg_restore, compares every public table's count/content fingerprint plus a pre-mutation JSON/vector witness in a separate database, and retains the transcript. Five local shell boundary tests passed, `bash -n`, workflow YAML parsing and `git diff --check` passed. **IMPLEMENTED_UNVERIFIED** for the real restore until exact-SHA CI executes. This closes no WAL/PITR, volume-recovery, production capacity/RPO/RTO or restored-application MCP requirement. Continue other independent actionable requirements while CI runs.

### Shared-budget CI and structured servlet correlation — 2026-11-26

Shared-budget commit `5c835622803087a7445ed1693dd8dc1b00483c88`: PR run https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45676761869 passed. Downloaded XML: **244 unit + seven PostgreSQL cases**, no failures/errors/skips. Migration V11 under restricted role and actual concurrent budget tests executed. A later manual dispatch of the same SHA also passed; future pushes already trigger the open PR workflow, so inspect that run instead of dispatching a duplicate. Restore-drill commit `0ec1591db98c40a5e75334bce083adcc596bcce4` is under PR CI at 45677646972.

OPS-METRICS remains PARTIALLY_IMPLEMENTED. Added server-owned UUID response/request correlation before security and explicit REQUEST/ASYNC/ERROR dispatch registration; restoration of MDC after exceptions prevents cross-request leakage. Console logs use the installed Boot 4 Logstash structured formatter. Only dispatch/status/async/elapsed fields are recorded, not URI/header/query/source values. Three focused tests and **247 full Java21 unit tests passed**, no failures/errors/skips (40.231s). The existing PostgreSQL fixture additionally requires UUID correlation on every MCP exchange, preserving all authentication/tool/impact assertions. Service execution awaits next CI. Scope is servlet dispatch threads; arbitrary Reactor/tool/background propagation is not yet provided, and no distributed tracing is claimed. Staging scrape/alert validation remains externally blocked.

### Rolling-budget follow-up and resume checkpoint — 2026-11-26

Found a configuration-rollout edge case in the first shared-budget UPSERT: saturating at each replica's configured limit could let a lower-limit replica reduce a higher count. The counter now saturates at the common bound `2147483648`, strictly above every supported positive Java integer budget, and never rewinds within a window. PostgreSQL regression checks explicitly exercise a lower-limit replica and the universal saturation bound before expiry. This is an additional robustness fix; the previous green fixture used uniform configuration. Full Java21 suite after this correction: **247 passed**, zero failures/errors/skips, 42.441s; new SQL assertions await next exact-SHA CI.

Structured logging was moved from the production-only YAML document to the common document so actual test-profile CI also exercises the formatter. Multi-document YAML placement was checked explicitly. Current operational scope remains partial. Pending PR runs: restore 0ec1591 → 45677646972; initial correlation 6f3a0fb → 45678358611. Preserve both successful baselines and inspect outcomes; use automatic PR runs for subsequent pushes, with no duplicate dispatch. Next functional gaps remain XRI-HOP cross-repository method propagation, Gradle safe dependency resolution, and broader contract schema/compatibility semantics. External production staging, WAL/PITR, representative pilot capacity and OS containment are still not certified. Do not stop after a green slice or claim overall readiness.
