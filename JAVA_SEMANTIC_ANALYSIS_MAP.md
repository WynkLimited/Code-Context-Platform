# Java semantic analysis source-to-target map

| Target component | Current role | Data boundary / limitation |
|---|---|---|
| `GitRepositoryManager.filesAt(root, commit)` | Materializes the exact Git commit as source-file records | These records are the input for semantic snapshot; no working-tree files are read by this path |
| `IndexingService.processLocked` | Creates generation, indexes changed files, runs Graphify, semantic scan, contract extraction, Zoekt, then publishes | Semantic scan runs before publication and failure leaves the generation unpublished |
| `MavenClasspathResolver` + `JavaSemanticCallSiteAnalyzer` | Resolves effective module classpaths without executing build plugins, then resolves calls, declarations, constructors, and method references with JavaParser | Controlled local Maven repository fixture passes. Parent/reactor behavior and broad production repository cases remain unverified |
| `V4__semantic_call_sites.sql` + `V5__semantic_call_graph.sql` + V6 Maven schema | V4 stores source/call data; V5 adds generation-scoped canonical method symbols and caller-to-target edge rows; V6 records module classpaths and resolved coordinates | `SharedLibraryImpactPostgresIT` exercises real generation persistence and verifies rows against PostgreSQL 17 + pgvector; the test compiles but awaits CI execution |
| `GraphSymbolBinder` | Binds extracted contract entries to code nodes by graph id, symbol, source range, and explicit ambiguity | It remains a separate contract-to-node binder; semantic call-site rows are not yet fed into it |
| `CodeContextTools.get_neighbors` | Returns regular code edges and generation-published V5 semantic caller/target edges after repository authorization | SQL execution against the V5 schema has not been live-verified |
| `CrossRepoImpactResolver` | Resolves contract imports/re-exports and looks up exact V5 caller edges for removed provider method identities and matching Maven artifacts | Provider comparison loads successful historical generations even after they are unpublished; consumer edges must still belong to the current published generation and exact indexed commit. PostgreSQL acceptance execution is pending CI |
| `CodeContextTools.impact_analysis` | Returns generation diff, contract compatibility, dependency candidates, and semantic method evidence | A real streamable HTTP MCP request is part of the PostgreSQL acceptance test; live verification awaits CI |

## Chosen Java resolver

JavaParser Symbol Solver was selected over Eclipse JDT because JavaParser Core is already used for Java parsing/indexing in this application, its Symbol Solver version can be pinned to the same release, and its source/JAR type solvers support incremental integration without replacing the indexing architecture. No custom overload-resolution rules were added. Solver exceptions become unresolved evidence; the analyzer never falls back to name matching.

## Remaining correctness work

Run the shared-library fixture through real provider/consumer indexing, V5 persistence, the impact SQL, and the MCP response against PostgreSQL. Expand Maven tests for parent/module inheritance, scopes, exclusions, transitives, repositories, and conflict mediation; validate bounded downloads; and run mixed Java/Kotlin semantic resolution against compiled artifacts. Until the DB-backed path is executed, the successful local fixture proves classpath binding and graph identity consistency, not production impact-analysis delivery.

## Acceptance runner status — 2026-11-24

Maven Surefire selects `SharedLibraryImpactPostgresIT` under `-Ppostgres-integration`; CI's `verify` command executes it in the `test` phase against PostgreSQL 17 + pgvector. Failsafe is not configured. The local rerun was blocked before test execution because only Java 17 is available while the project targets Java 21; Docker is also unavailable. V4/V5/V6 database rows, impact SQL, MCP protocol session, and PostgreSQL failure/rollback remain unverified. Do not mark acceptance passed until a CI Surefire report confirms execution.
