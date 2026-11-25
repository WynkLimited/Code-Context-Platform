# Product feature matrix

Statuses reflect ledger evidence. `N/A` requires justification.

| Feature | Developed | Integration | E2E MCP | Security | Performance | Evidence | Status |
|---|---|---|---|---|---|---|---|
| Text index (Zoekt) | Y | Y | Y | Partial | Partial | SharedLibrary IT | VERIFIED (func) |
| Code graph (Graphify→PG) | Y | Y | Y | Partial | Partial | GraphifyPostgresIT | VERIFIED (func) |
| Contract/dependency index | Y | Partial | Partial | Partial | N | Unit+shared REST/Kafka | PARTIAL |
| Semantic index | Y | Y | Y | Partial | Partial | SharedLibrary + Ollama | VERIFIED (func) |
| MCP search_code | Y | Y | Y | Y (ACL) | Partial | SharedLibrary IT | VERIFIED |
| MCP read_file | Y | Y | Y | Y (ACL/path) | Partial | SharedLibrary IT | VERIFIED |
| MCP find_symbol | Y | Y | Y | Y | Partial | SharedLibrary IT | VERIFIED |
| MCP get_neighbors | Y | Y | Y | Y | Partial | SharedLibrary IT | VERIFIED |
| MCP semantic_search | Y | Y | Y | Y | Partial | SharedLibrary IT | VERIFIED |
| MCP impact_analysis | Y | Y | Y | Y | Partial | Java+Kotlin IT | PARTIAL (hops/Gradle) |
| Repo lifecycle / purge | Y | Y | Y | Partial | N | Retention IT | VERIFIED (core) |
| Webhook ingest | Y | Unit | Partial | Partial | N | Controller tests | PARTIAL |
| Java↔Kotlin shared-lib impact | Y | Y | Y | Y | Partial | Kotlin provider IT | VERIFIED (ABI) |
| REST OpenAPI evolution | Y | Unit | Pending PG | Partial | N | Nested $ref unit | PARTIAL |
| Kafka Avro evolution | Y | Unit | Pending PG | Partial | N | Extractor→compat unit | PARTIAL |
| Gradle resolution | N | N | N | N/A | N/A | — | NOT_IMPLEMENTED |
| OS sandbox | Partial | N | N | BLOCKED | N/A | App isolation only | BLOCKED |
| DR / PITR | Scripts | N | N | Partial | N | Scripts only | BLOCKED (env) |
| MultiRepo independence | Y | Unit | Y (CI build) | Y | N/A | ArchitecturalIndependenceTest | VERIFIED |

Overall: **NOT READY FOR PRODUCTION** — see ledger.
