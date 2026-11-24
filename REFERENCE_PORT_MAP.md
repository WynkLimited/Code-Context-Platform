# Reference audit and port map

Repository A: `/Users/b0266431/projects/MultiRepo-Code-Review-Agent` (read-only)
Repository B: this repository; it has its own Maven build and lifecycle.

## Source audit findings

The reference is a Java 17 Maven multi-module review agent. The relevant logic is in `ugp-review-agent-java/review-agent-core` with wiring in `review-agent-app` and Git workspace utilities in `review-agent-integrations`. It is not a Spring Boot platform and cannot be used as a runtime or build dependency.

Audited source components:

| Reference source | Behavior found | Target status |
|---|---|---|
| `contract/ContractIndexBuilder`, `ContractModels`, `ContractIndexHasher`, `ContractEntryMetadata` | Versioned per-commit index, deterministic sorting/hash and enriched evidence | Ported under `port/contract`; wired into target indexing and persisted via V2 migration. Graph binding remains absent. |
| `contract/extract/*` (REST, Kafka, Maven, proto, gRPC, enum, config, database, cache, string format) | Contract extraction with provenance and canonical identities | All extractor classes are ported, registered, invoked against the synced repository snapshot, and persisted. Regression tests pass; target database execution and semantic parity remain unverified. |
| `contract/ContractIndexEnricher`, `ContractEntryMetadata`, `graph/GraphSymbolBinder` | Stable symbols, evidence class, optional graph binding | Metadata enrichment is ported. Target `GraphSymbolBinder` binds against generation-local AST/Graphify nodes, prefers exact symbol/source range, narrows by source span, and persists explicit ambiguity/unresolved status. Unit tested; database/runtime binding remains unverified. |
| `contract/compat/ContractCompatibilityEngine` | Deterministic compatibility/change classification | Ported with reference regression tests and wired into MCP generation-to-generation impact analysis. Unknown classifications stay explicit and non-blocking. |
| `contract/graph/ContractGraphModels`, `CrossRepoImpactResolver`, `MavenConsumerBridge` | Bounded candidate routing and graph construction | Target checks team scope and index freshness, limits candidates to 500, walks up to three explicit re-export hops, validates Maven artifact ownership against exact provider generation coordinates, and queries published V5 edges for removed provider method identities. A PostgreSQL integration test exercises this from actual indexing; it compiles but awaits CI execution. |
| `crossrepo/CrossRepoImpactAnalyzer`, `ConsumerContractIndexRefresher` | Consumer routing, source evidence selection, optional LLM findings, validation/provenance and blocking veto | Target keeps deterministic compatibility/resolution/refresh and does not port the LLM review pipeline or veto. Maven-resolved external Java call sites now carry exact caller/target method identities into V4/V5 and are returned as potential upgrade impacts; this does not prove current breakage. Kotlin remains unsupported. |
| `contract/ContractIndexStore` | Immutable per-commit indexes, atomic latest pointer and freshness | Adapted as `PostgresContractIndexStore`; reads current/previous successful generations, verifies index hashes and extractor/schema/rules versions. No live PostgreSQL test was possible. |
| `contract/source/JavaKotlinStructureParser`, `JavaApiMethodParser`, identity and tree-sitter parsers | Java/Kotlin source structure and parser evidence | Parser sources are ported and regression tests pass. Cross-platform Kotlin grammar availability and full integration with contract graph binding need verification. |
| Java API semantic resolution | Reference source parsers provide API shape and evidence; the reference analyzer selects consumer files before analysis | Target adds `MavenClasspathResolver` and `JavaSemanticCallSiteAnalyzer` over exact-commit source roots plus per-module resolved JARs. V4/V5 persist canonical caller-target identities with Maven artifact/version evidence. `SharedLibraryImpactPostgresIT` now covers V4/V5/V6 persistence and MCP response from PostgreSQL but has not yet run. |
| `crossrepo/OrderedBoundedExecutor` | Ordered bounded parallel task execution | Not ported |
| `git/RepoWorkspace`, `git/ProcessRunner` | Safe checkout, locks, timeout and workspace cleanup | JGit sync plus URL/path validation is implemented; cross-process mutation lock is incomplete |

Reference regression suites inspected include `CrossRepoImpactAnalyzerTest`, `ConsumerContractIndexRefresherTest`, `ContractIndexStoreTest`, `ContractCompatibilityEngineTest`, REST/Kafka/Maven/proto extractor tests, and Java/Kotlin conformance suites. The compatibility, core extractor, contract identity/hash, GraphSymbolBinder, and Maven bridge suites are present and run in Repository B. The full reference analyzer/refresher/store integration and Java/Kotlin conformance suites have not been ported as end-to-end tests.

## Target mapping

- `repo/GitRepositoryManager`: GitHub/local-fixture clone, sync, exact commit reads, path validation.
- `index/IndexingService`: durable jobs, byte-hash incremental node/chunk reuse, embeddings, exact-snapshot contract extraction, graph binding, contract-index persistence, relationship refresh and generation publication.
- `index/ConsumerContractIndexRefresher`: generation-local link recomputation, superseded-link pruning, bounded asynchronous scheduling of relevant stale or unindexed peers.
- `port/contract/PostgresContractIndexStore`: current/previous successful index reconstruction and freshness validation.
- `port/contract/graph/CrossRepoImpactResolver`: bounded team-scoped contract routing, explicit re-export traversal, and exact V5 removed-method lookup gated by provider Maven coordinates.
- `index/MavenClasspathResolver` + `port/contract/source/JavaSemanticCallSiteAnalyzer` + V4/V5/V6: per-module Maven classpaths, external symbol binding, canonical method identities, persisted artifact coordinates/version and semantic edges. The PostgreSQL shared-library integration test drives the complete path and is wired into CI; execution is pending.
- `index/GraphifyIndexer`: isolated structural CLI run and Graphify node/edge import.
- `search/ZoektIndexer` + `search/ZoektSearchService`: generation-specific index build and team-filtered Zoekt search.
- `mcp/CodeContextTools`: six MCP capabilities; impact analysis returns semantic method evidence from the resolver. A streamable HTTP MCP acceptance call is included in the PostgreSQL integration test; runtime remains unverified until CI runs. Results are informational and non-blocking.
- `db/migration/V1__initial_schema.sql` through `V6__maven_module_classpaths.sql`: registry, team grants, generations, code graph, versioned contracts, persisted impact results, and Maven module/dependency metadata. Flyway has not been run against a live PostgreSQL service in this environment.

This is an explicit source-to-target plan, not a claim that full behavior preservation is complete. Port the reference's extractor and regression logic before using the target's impact output as a blocking release signal.

## Current validation status

The target compiled on Java 21 and 124 Maven unit/regression tests passed on the latest completed run. The controlled Maven fixture confirms external method and canonical V5 identities. `SharedLibraryImpactPostgresIT` compiles and is wired to CI's PostgreSQL 17 + pgvector service; it asserts v1 consumer A's exact method is the sole semantic upgrade hit while v2 consumer B and the stable overload consumer C are excluded. The test also exercises persisted impact output through MCP and a failed-publication boundary. It has not run; live database and MCP behavior remain unverified. Repository A remains read-only.

Execution audit on 2026-11-24: Surefire, not Failsafe, owns `*IT.java` in the `postgres-integration` profile; CI runs it with `mvn -Ppostgres-integration verify` after its separate default unit-test command. Local execution stopped before tests because the available JDK is 17 (release 21 required); Docker has no reachable daemon and GitHub CLI credentials are invalid. No PostgreSQL-backed acceptance or CI run is claimed.
