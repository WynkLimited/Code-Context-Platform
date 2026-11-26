# Production readiness ledger

**Authoritative** production-readiness progress for Code Context Platform.  
Updated continuously. Do not mark `VERIFIED_PRODUCTION_READY` without executable evidence.

| Field | Value |
|---|---|
| Branch | `fix/jackson3-postgres-acceptance` |
| Local/remote HEAD (start of this closure) | `9bd5059007e302a409cc8f413026b6844df7efd8` |
| Last green mandatory CI SHA | `fc6026f6f998efb34a6a64af20a2b90a82e2e697` |
| Green CI URL | https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45685301216 |
| Prior failed CI | a374923/08c449c run 45636811199 — dual-ctor wiring; fixed in ab3dcbb |
| Overall decision | **NOT READY FOR PRODUCTION** |
| Working progress companion | `docs/IMPLEMENTATION_PROGRESS.md` |
| Reference repo (read-only) | `WynkLimited/MultiRepo-Code-Review-Agent` @ `main` / `65144c6060e97c491546674586ea8ae5020588c7` |

Status vocabulary (this ledger):  
`NOT_IMPLEMENTED` · `PARTIALLY_IMPLEMENTED` · `IMPLEMENTED_UNVERIFIED` · `TEST_FAILED` · `BLOCKED` · `VERIFIED_PRODUCTION_READY` · `NOT_APPLICABLE_WITH_JUSTIFICATION`

---

## Resume checkpoint

**Session progress (this closure):** Ledger + MultiRepo parity; proposed-change MCP; independence gate; webhook freshness+PG dedupe; nested+cross-file OpenAPI; Avro/OpenAPI MCP evolution; hop incompleteness flags; admin key status; REST ambiguity PG; subprocess env scrub; mandatory CI green @ `f0a36ad`.

**Overall:** **NOT READY FOR PRODUCTION** — actionable implementation gaps (including Gradle, arbitrary cross-repository method propagation, semantic chunk reuse/provenance and full-application concurrency drills) remain separate from external staging, OS containment, production recovery and representative pilot validation.

**Next executable actions (priority):**

1. Method-chain multi-hop beyond re-export candidates (XRI-HOP) — incompleteness flags already exposed.
2. Full-application multi-instance crash/heartbeat drills (IDX-1/2) beyond the verified separate-JVM lease/publication workers.
3. Semantic chunk correctness, model provenance and reuse within edited files; safe Gradle resolution (XRI-GRADLE), Maven reactor visibility and lifecycle onboarding depth.
4. External blockers: OS sandbox, production DR/PITR drill, k8s smoke and representative pilot capacity/client evidence.

---

## Requirement register

### Context foundations

| ID | Category | Requirement | Status | Implementation | Evidence / next action |
|---|---|---|---|---|---|
| CTX-1 | Text index | Zoekt exact/regex search with evidence | VERIFIED_PRODUCTION_READY | `ZoektSearchService`, `ZoektIndexer` | CI b2346ee SharedLibrary IT |
| CTX-2 | Code graph | Graphify → PostgreSQL nodes/edges | VERIFIED_PRODUCTION_READY | `GraphifyIndexer`, `GraphifyPostgresIT` | CI b2346ee |
| CTX-3 | Contract/dependency index | REST/Kafka/Maven/proto contracts + links | PARTIALLY_IMPLEMENTED | extractors + shared IT links | Nested+cross-file OpenAPI `$ref` (1.3.0); Avro MCP evolution @ f905835 |
| CTX-4 | Semantic retrieval | Ollama embeddings + pgvector MCP | VERIFIED_PRODUCTION_READY | indexing chunks + `semantic_search` | CI a176f6e/b2346ee real Ollama; all subsequent seven-case PG gates retained |
| CTX-4A | Semantic chunk construction | Accurate source ranges, bounded structural/heading chunks | PARTIALLY_IMPLEMENTED | `JavaSourceText`, `BoundedTextChunks`, `IndexingService` | Original design PDF pp. 3–4; Java/source-window regressions pass locally, new persisted-range assertions await CI; tiny-piece merges, heading/config sections and token-aware splitting remain open |
| CTX-4B | Embedding provenance/reuse | Reuse unchanged chunks inside edited files with model/config identity | PARTIALLY_IMPLEMENTED | Existing unchanged-file copying; generator epoch invalidation | Original PDF incremental promise; model digest/config provenance and safe cross-file-edit chunk reuse remain actionable |

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
| LIFE-1A | Automatic webhook registration / GitHub App ingress | PARTIALLY_IMPLEMENTED | Path-specific webhook ingestion is verified; original design onboarding promises automatic registration. Registration/global App ingress not found; implement and test locally, actual provider installation requires operator credentials |
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
| OPS-METRICS | Domain meters + alert YAML | PARTIALLY_IMPLEMENTED | Structured servlet correlation verified in df9b83d CI; scoped worker identity implemented locally; service evidence and live scrape/alerts remain open |
| OPS-BACKUP | Scripts + docs | PARTIALLY_IMPLEMENTED | Logical restore CI verified at 0ec1591; production WAL/PITR and volume/application recovery remain external |
| OPS-K8S | Manifests | PARTIALLY_IMPLEMENTED | Cluster smoke BLOCKED |
| OPS-PERF | Pilot ≤1 min searchable | PARTIALLY_IMPLEMENTED | Representative benchmark open |

### Validation

| ID | Requirement | Status | Evidence |
|---|---|---|---|
| VALID-CI | Mandatory Java21 + PG ITs | VERIFIED_PRODUCTION_READY | fc6026f: 265 unit + seven PostgreSQL + one actual HTTPS case; five shell tests and actual logical restore passed |
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

### Accepted recovery/observability evidence and OpenAPI scalar domains — 2026-11-26

`df9b83d7b8bcfb4cec274779cbd5cd5116c24b69` passed https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45678687700. XML: **247 unit + seven PostgreSQL cases passed**, zero failures/errors/skips; five shell boundary tests and actual logical restore gate also passed. The restricted-role PostgreSQL fixture verified monotone counters across lower-budget replicas and the universal saturation bound. Parsed actual Maven log JSON: **44 HTTP dispatch events with valid UUID request_id**. Thus common-profile structured logging and actual authenticated/rejected MCP correlation are service-verified. `0ec1591` restore transcript additionally shows all public table content/count fingerprints matched, including populated semantic symbols/edges and saved impact data, plus the pre-mutation JSON/vector witness. This is CI fixture recovery evidence only, not production WAL/PITR or RTO/RPO certification.

**Contract enum slice:** confirmed scalar OpenAPI enums previously normalized to literal `enum`, losing value-set changes from both signature and content hash. `ScalarEnumDomain` preserves canonical, delimiter-safe finite values; duplicates/order and numeric spelling normalize consistently. Request-domain narrowing and response-domain widening are BREAKING; inverse movement is backward compatible within the supported explicit scalar/format domain. Finite/unrestricted transitions follow the same inclusion rule. Required-field changes cannot be hidden by a compatible enum movement; response required→optional is breaking. Kafka's existing cardinality policy remains unchanged.

Explicitly unsupported null/mixed/object/oversized enum domains and extra scalar validation become hashed opaque tokens with `schemaComplete=false`; changed opaque domains remain UNKNOWN. Array-contained enum evolution is UNKNOWN until its structural semantics are modelled. These limitations are not compatibility proofs. REST extractor version is 1.4.0 and compatibility rules 1.8.0; existing persisted indexes need reindexing under the standard freshness rules. No database migration or historical migration rewrite is involved. Normalization follows the OpenAPI schema/enum constraints at https://spec.openapis.org/oas/v3.0.4.html#schema-object; direction-aware inclusion is this platform's documented compatibility policy.

Nine new regression cases cover request/response directions, unrestricted transitions, punctuation/Unicode, numeric equivalence, unsupported constraints/domains, array limits and combined cardinality changes. Final focused extraction/compatibility suite: **58 passed**. Complete Java21 suite: **256 passed**, zero failures/errors/skips (37.705s). The shared PostgreSQL test now requires both enum request-narrowing and response-widening BREAKING changes through actual HTTP MCP without changing its original exact consumer/security/indexing assertions. Those new service assertions are IMPLEMENTED_UNVERIFIED until next exact-SHA CI.

**Persistent context:** recovered master directive is now also retained locally in ignored `.agent-handover/RECOVERED_MASTER_DIRECTIVE.md`, alongside the original Cursor transcripts. Continue in this checkout and branch only. Next: verify enum CI, then remaining multi-module Maven correctness/performance, safe Gradle resolution, full cross-repository caller propagation, worker correlation and operational/capacity requirements. A new concrete scalability observation is repeated reactor effective-model construction (quadratic work); module source visibility and sibling dependencies also require focused examination before changing them. These are actionable code gaps, not external blockers. Production remains NOT READY.

### Reactor model construction — 2026-11-26

`MavenClasspathResolver` formerly rebuilt all reactor effective models inside every module loop, in addition to each module's own model. It now constructs each model once in a per-resolution local map and reuses the coordinate set; no state survives into another repository or commit. This removes the explicit N² outer-loop model reconstruction without changing dependency repository policy or executing build plugins. A real 12-child-module + parent fixture uses an approved local artifact repository, BOM imports and inherited dependency versions, then changes the parent BOM version and resolves again with the same resolver. All modules select the correct new artifact rather than reusing the previous commit's model. Focused MavenClasspathResolverTest: **4 passed**, no failures/errors/skips. This is functional and algorithmic evidence, not a measured production indexing latency improvement. Positive sibling-source classpaths and module visibility remain open. Enum df26d13 PR CI is running at 45680364704; continue monitoring it.

### Maven policy boundary regressions — 2026-11-26

Enum commit `df26d13493d8378776f888b1546f7c8cb0090a12` passed https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45680364704. Downloaded XML: **256 unit + seven PostgreSQL cases passed**, no failures/errors/skips. Both new OpenAPI enum classifications executed through authenticated HTTP MCP. Script boundary and real logical restore gates also passed.

Reproduced four Maven trust-boundary failures with controlled temporary fixtures, without touching the reference repository or any real private file: (1) an artifact descriptor injected an unapproved file repository and resolution incorrectly succeeded; (2) a parent relative path outside the snapshot was consumed; (3) an approved BOM path followed a symlink to an outside file; (4) changing a repository URL while retaining its ID still trusted an artifact in the shared local cache. Pre-fix failures are retained in `/tmp/ccp-descriptor-before.log`, `/tmp/ccp-model-containment-before.log`, `/tmp/ccp-cache-revocation-before.log`.

Fixes: ignore artifact-descriptor repositories on the actual Aether session; contain Maven ModelSource2 related-parent reads; require symlink-free approved model files and exact reactor GAV matching; bounded secure XML validation (16 MiB, 128 levels, no DOCTYPE/entities/XInclude); isolate local artifact caches by policy epoch plus ordered operator repository URLs/IDs/host sets. Previously unscoped cache content is not reused. Generator 1.7.0 makes old persisted contract evidence stale until reindexing. Existing approved local artifact resolution, 12-module parent/BOM inheritance, version changes across commits and exact Money signatures still pass. Nine focused Maven tests passed, including actual Aether downloads and cache reuse/revocation rather than mocks. These fixes do not establish OS containment, private-egress enforcement or support for HTTPS parent/BOM model fetching; those remain open.

Full Java21 suite after Maven policy/model/cache corrections: **262 passed**, zero failures/errors/skips, 39.057s. `git diff --check` passed. PostgreSQL acceptance for these changes remains pending automatic PR CI after publication of the scoped model-cache and security commits. Continue with exact-SHA CI validation, then HTTPS parent/BOM model support and correct declared reactor source visibility, without relaxing the repository allowlist or running untrusted Maven/Gradle build lifecycles.

### Secure HTTPS parent/BOM support — 2026-11-26

`78f033922af1b8f955bb36a4794886372bf19be9` passed https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45682228272. Downloaded XML: **262 unit + seven PostgreSQL cases passed**, zero failures/errors/skips; script and actual logical restore gates passed. Thus the prior model/cache/policy regressions and core HTTP MCP/rollback scenarios are accepted at this SHA.

Implemented approved HTTPS parent/BOM fetching using the actual RepositorySystem, contained cached models and a per-resolution model budget/cache. Actual network testing against Maven Central resolves Apache parent 35, JUnit BOM 5.11.4, commons-lang3 3.17.0 and transitive jars, then binds exact StringUtils/Assertions calls. The first real run exposed missing JDK-profile activation input (`Failed to determine Java version for profile jdk9+`); corrected using only five nonsecret JDK/OS properties. Private JVM/environment properties and host Maven settings are not imported; unresolved coordinates fail closed.

Maven Resolver modules upgraded together from 1.9.22 to 1.9.26 because the supported HTTP redirect-denial switch is available in the patch line. `dependency:tree` confirms all eight Resolver modules at 1.9.26, with existing Maven provider/model-builder 3.9.9 unchanged. Actual HTTP transport test proves 302 refusal and zero requests to the target; production HTTPS/host/private-address checks are preserved. Supported switch documented at https://maven.apache.org/resolver-archives/resolver-1.9.26/configuration.html. DNS-to-connect race/egress enforcement remains a separate platform boundary.

A new controlled corruption test reproduced warning-only checksum acceptance before the policy change (`/tmp/ccp-checksum-before.log`). Downloads now use FAIL checksum policy. Both mismatched and absent checksum variants fail, including retries against the same cache. Policy epoch 3 avoids reusing earlier transport-policy caches; generator 1.8.0 invalidates older persisted evidence, and V6 metadata names the actual resolver/policy version. No applied migrations were changed.

Final focused validation: **13 passed** (11 Maven classpath/policy tests, one actual redirect transport test, one actual HTTPS integration). Complete default Java21 suite: **265 passed**, zero failures/errors/skips, 41.807s. POM parsed, dependency tree inspected, CI YAML parsed and `git diff --check` passed. CI now requires exactly one successful/unskipped MavenRemoteModelIT in addition to all existing gates. This new gate is IMPLEMENTED_UNVERIFIED in CI until the next pushed SHA passes; local real HTTPS execution has passed. Full private-repository authentication, declared reactor sibling-source visibility, transitive scope correctness, bounded aggregate transfers, Gradle and arbitrary cross-repository caller propagation remain actionable; no overall readiness claim.


### Semantic input integrity — 2026-11-26

**Accepted preceding baseline:** exact SHA `fc6026f6f998efb34a6a64af20a2b90a82e2e697`, run https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45685301216 succeeded. Downloaded Surefire XML contains **265 unit + seven PostgreSQL + one real HTTPS case**, all successful with zero skips; shell boundary and actual logical restore gates also passed. This verifies the previously pending secure HTTPS parent/BOM gate.

Reconciled the complete 14-page original design PDF (`Code-Context-Platform-How-Context-Is-Built-and-Used.pdf`, Nov 22 design) against current code. Its chunking, edited-file chunk reuse, webhook onboarding and pilot criteria are product requirements, not evidence that this implementation satisfies them. Prototype latency/capacity figures are not production measurements. Split CTX-4 so accepted semantic retrieval remains verified while incomplete construction/reuse requirements stay visible.

**Implementation:** real HTTP request regression reproduced ignored `spring.ai.ollama.embedding.num-ctx` under Spring AI 2.0.2. Provider property metadata lacks this binding; generic embedding options also lose provider defaults during its merge. `OllamaEmbeddingConfiguration` supplies one model using the existing auto-configured API, preserves model/keep-alive, model initialization and observation settings, explicitly sets context and refuses silent truncation. A request adapter translates generic options to provider options. Wire assertions require configured model, context, keep-alive and `truncate=false`, not just a bean's presence.

Java chunks now slice original AST source ranges, retain associated documentation and actual physical line numbers, and include the declaring class in method chunk identity. Overlap shrinks when necessary to obey the character budget; terminal delimiters do not invent lines; long-line fragmentation preserves surrogate pairs. Node identities/ranges and public MCP contracts are unchanged. Generator 1.9.0 invalidates earlier cached source chunks without deleting the last published generation; a new acceptance case poisons a legacy chunk while retaining unchanged Git blob records and requires a clean rebuild. A separate persisted inline-method assertion requires exact original text and line 3→3.

**Validation:** eleven focused regressions and complete Java21 default suite **276 passed**, zero failures/errors/skips (46.889s). `git diff --check` passed. New PostgreSQL range/generator assertions are **IMPLEMENTED_UNVERIFIED** until the exact-SHA CI passes. No local PostgreSQL acceptance claim; Docker service execution remains on CI. No migrations, authentication, tool registration or reference repository changed. Full token-aware chunking, tiny-piece merging, Markdown/config sections, model digest/config provenance and edited-file chunk reuse remain open, not external blockers. Overall NOT READY FOR PRODUCTION.


### Bounded-memory artifact hashing — 2026-11-26

Reproduced a real `OutOfMemoryError` in production artifact hashing with a 64 MiB file and a separate JVM limited to 24 MiB heap. Resolver previously used `Files.readAllBytes` for every persisted dependency checksum despite accepting artifacts up to 512 MiB. It now hashes through a fixed 64 KiB buffer with a no-follow input stream. The existing 512 MiB bound remains enforced before reading and is also checked during streaming, covering file growth. SHA-256 contents, persisted fields and resolver policy/version remain unchanged; no generator or migration change is needed.

Three new regressions exercise the constrained child JVM with an independently computed exact digest, the standard SHA-256 `abc` vector and an equal-size content change, plus sparse oversized-file rejection. Focused suite **15 passed**, including actual HTTPS Maven resolution. Complete Java21 default suite **279 passed**, zero failures/errors/skips (41.486s); `git diff --check` passed. This demonstrates bounded hash-buffer memory, not bounded Maven network downloads, aggregate classpath size or production capacity. Those separate requirements remain open. Preceding semantic-input CI for `edfd9df` is running at https://github.com/WynkLimited/Code-Context-Platform/actions/runs/45751313386; new PostgreSQL assertions remain pending until the actual run completes.


### Durable worker log identity — 2026-11-26

Indexing attempts now establish a worker-local logging scope with typed UUID job/repository IDs and numeric attempt; the actual synchronized 40-character Git commit and generation UUID are added when known. Structured start/finished events delimit each dispatch, including busy/retry/error exits; finished does not assert successful publication. The scope clears unrelated inherited context, including servlet request identity, and restores the original thread context through try-with-resources. It never copies request headers, arguments, query parameters or source. Existing lease/publication/retry decisions are unchanged.

Three new tests verify exact allowed initial fields, exceptional cleanup/restoration, rejection of unsafe identities and sequential reuse of a real executor thread without previous commit/job leakage. Focused **3 passed**; complete Java21 default suite **282 passed**, zero failures/errors/skips (40.703s); `git diff --check` passed. Actual structured worker events in CI remain IMPLEMENTED_UNVERIFIED until inspection of the next exact-SHA service log. This is durable-job correlation, not arbitrary Reactor propagation or distributed tracing. No migrations/security/tool contracts changed. Source-integrity and checksum PR runs remain in progress; do not claim their new PostgreSQL assertions accepted yet.
