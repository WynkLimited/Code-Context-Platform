# Production readiness matrix

Status reflects executed evidence in this workspace on 2026-11-24. “Implemented” means code/configuration exists; it does not imply runtime verification. Production readiness has **not** been established.

| Requirement | Existing implementation | Remaining gap / required change | Verification method | Actual status |
|---|---|---|---|---|
| Java 21 service and independent build | Spring Boot/Maven application; no dependency on reference checkout | CI/release gates and reproducible image scan | Java 21 Maven full test | Historical run: 124 unit/regression tests passed; this environment's rerun unavailable (JDK 17) |
| Repository onboarding and exact commit reads | JGit mirror/sync, allowlists, commit-tree reads | Adversarial URL/SSRF and cross-process concurrency tests | Local fixture, malicious URL tests | Narrow unit coverage; security unverified |
| Structural indexing | JavaParser and Graphify importer; Tree-sitter parser adapters | Bound memory, persisted graph validation, Kotlin grammar/runtime checks | Live indexing and graph DB assertions | Narrow Graphify fixture test; DB path unverified |
| Exact and regex search | Zoekt indexer/search service | Runtime indexing/search, update/delete/rename and auth tests | Compose Zoekt acceptance | Not run; Docker daemon unavailable |
| Contracts and compatibility | Registered extractors, compatibility engine, exact-generation Maven coordinate ownership check, and V5 semantic-impact query | Live provider/consumer persistence and SQL execution against PostgreSQL; Maven parent/reactor edge cases | Shared-library Maven fixture plus provider/consumer DB acceptance fixture | Controlled Maven-to-method identity passed; DB-backed impact remains unverified |
| Java call-site evidence | JavaParser Symbol Solver runs on exact-commit source snapshots and Maven-resolved module classpaths; V4/V5 carry canonical declaration/caller/target keys | Live V5 persistence and query lifecycle | Maven shared-library fixture asserts `Money.convert(java.lang.String)` and both canonical V5 keys | Classpath-to-method fixture passed; database path unverified |
| Kotlin/JVM interop | Kotlin parser adapters and Graphify node import | Compiler/PSI semantic analysis and Java/Kotlin interoperability | Compiled mixed-language fixtures | Not verified; no semantic resolver |
| Cross-repository impact | Bounded team-scoped resolution, compatibility comparison, freshness statuses, persisted result, and V5 removed-method edge lookup | Execute exact shared-library fixture through persisted provider/consumer generations, impact SQL and MCP output; live refresh lifecycle | `SharedLibraryImpactPostgresIT`: v1 consumer A affected, v2 consumer B and stable overload consumer C excluded | PostgreSQL/MCP test implemented and compile-verified; runtime pending CI; remains non-blocking |
| PostgreSQL 17 and pgvector | Compose image, Flyway V1–V6, and a `postgres-integration` Surefire profile selecting `*IT.java` | Execute migrations, V5/Maven persistence, impact SQL, rollback, HNSW and similarity queries | CI pgvector service plus `SharedLibraryImpactPostgresIT` | One integration test compiled; zero DB tests executed. Local daemon unavailable; CI requires an authenticated push/dispatch |
| Ollama semantic search | Local Ollama model responds; Spring embedding/vector code exists | End-to-end chunk/query embedding, model metadata, persistence, retrieval and ACL | App plus PostgreSQL real-vector test | Model endpoint reachable; application path unverified |
| Six MCP tools | Tool definitions, team authorization checks, and streamable HTTP transport | Verify actual database-backed impact response, all tool calls, pagination, errors, timeouts and tenant isolation | MCP HTTP session in `SharedLibraryImpactPostgresIT` | End-to-end test implemented and compile-verified; runtime pending CI |
| Webhooks and durable jobs | HMAC webhook, delivery dedupe, queue, worker retry and publication paths | Replay/out-of-order, crash recovery, leases, dead letters, reconciliation | Fault-injection integration suite | Implemented in part; reliability unverified |
| Atomic indexing and search publication | DB generation publication and Zoekt generation indexing | Prove consistency/recovery across DB and Zoekt failures | Crash injection at each publication boundary | Not verified |
| Team/repository security | API key filter, ACL joins, origin filter, Git/path controls | Adversarial tenancy, SSRF, POM, path, resource and secret tests | Security integration suite/review | Unit coverage narrow; not security-verified |
| Monitoring and operations | Actuator, Micrometer, docs/runbooks | Stage metrics, correlation, alerts, dashboards, restore drills | Live service/metrics and recovery exercises | Partial; not operationally verified |
| Local development | Compose services, fixture repos and onboarding script | Docker smoke, signed webhook simulator and repeatable status/reset commands | `docker compose up` and full fixture run | Not run; Docker daemon unavailable |
| Kubernetes deployment | K8s manifests for app/dependencies/networking | Manifest validation, storage/RBAC/TLS review, staging rollout/rollback | `kubectl`/policy validation and staging deploy | Templates exist; not deployed or verified |
| CI/CD and supply chain | Added `.github/workflows/ci.yml` for Java 21 Maven tests | Static analysis, vulnerability/secret/image scans, integration gates and release approvals still absent | CI run on PR | Workflow implemented; not executed |
| Capacity/performance | Bounded worker settings and candidate limits | Representative benchmark, memory/concurrency/freshness measurement | Repeatable benchmark suite | Not measured |
| Backup and recovery | Backup/recovery document | Restore drill and data consistency checks | Restore against disposable PostgreSQL | Documented; not tested |

## Environment evidence

- Maven suite: 124 unit/regression tests passed on Zulu JDK 21.0.13.1 after the historical-provider-generation lookup fix. The PostgreSQL integration test compiles but has not executed.
- Shared-library fixture: real Maven Resolver + JAR + JavaParser binding identified `Payment.run` calling `Money.convert(java.lang.String)` and checked provider/caller identities in the V5 key format. It did not execute PostgreSQL persistence or the impact query.
- Docker daemon: unavailable (`docker info` could not connect to the configured socket).
- PostgreSQL client/server and Zoekt local binaries: not found; no live service endpoint was available.
- Ollama `/api/tags`: reachable and reported the configured Qwen embedding model. No application-to-pgvector query was exercised.
- No MCP client session, security test campaign, performance benchmark, restore drill, Kubernetes deployment, or CI execution occurred.
- Runner audit (2026-11-24): Surefire owns `*IT.java` under `postgres-integration`, executed in the `test` phase of CI's profile-enabled `verify`; Failsafe is not configured. CI runs default unit tests separately first. The attempted local `mvn test` failed before tests with `release version 21 not supported`; Docker has no reachable daemon, and `gh auth status` reports invalid credentials. The DB acceptance result remains 0 executed / 1 previously compiled, and no CI run was inspected.
- Acceptance breakdown for this pass: Java 21 compile **not run**; existing unit/regression suite **not rerun** (124 pass is historical); PostgreSQL integration **0 executed / 1 selected test**; V4/V5/V6 live persistence **not verified**; resolver SQL **not executed**; MCP HTTP protocol **not executed**; PostgreSQL rollback/failure handling **not executed**. Active Git root is `/Users/b0266431/projects/Code-Context-Platform` on `main`; only report files are modified.

## Release decision

**Not production-ready.** The platform is compiled and unit-tested, but major runtime, semantic correctness, security, recovery, capacity, and deployment criteria remain unverified. Do not use impact results as a merge/release gate. Production approval requires closing the gaps above and obtaining successful evidence from real infrastructure.
