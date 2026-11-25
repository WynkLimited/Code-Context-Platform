# Cross-repository impact parity matrix

Reference: `MultiRepo-Code-Review-Agent` `main` @ `65144c6060e97c491546674586ea8ae5020588c7`  
Platform: Code Context Platform branch `fix/jackson3-postgres-acceptance`

Statuses: `FUNCTIONALLY_EQUIVALENT` · `PLATFORM_IMPLEMENTATION_SUPERIOR` · `PARTIALLY_EQUIVALENT` · `MISSING` · `IMPLEMENTED_UNVERIFIED` · `INTENTIONALLY_DIFFERENT_WITH_JUSTIFICATION` · `NOT_APPLICABLE_WITH_JUSTIFICATION` · `BLOCKED`

| Capability | Reference behaviour | Platform behaviour | Status | Platform evidence | Gap / next |
|---|---|---|---|---|---|
| Maven coordinate / import routing | POM + MavenConsumerBridge façade hop | Maven contracts + classpath resolver + bridge concepts | PARTIALLY_EQUIVALENT | `MavenContractExtractor`, `MavenClasspathResolver`, shared IT | Overlapping-class ownership fixture |
| Gradle dependency graph | Not production resolver | Not implemented | NOT_APPLICABLE_WITH_JUSTIFICATION | — | Track as XRI-GRADLE if product requires |
| Java method call evidence | Exact matchers + optional javac shadow | JavaParser Symbol Solver + V5 edges | PLATFORM_IMPLEMENTATION_SUPERIOR | SharedLibraryImpactPostgresIT | Keep |
| Kotlin JVM ABI / interop | Structure + interop replay; prod gate weak | kotlinc JVM ABI + K↔J MCP impact | PLATFORM_IMPLEMENTATION_SUPERIOR | Kotlin provider IT @ b2346ee | Source UNKNOWN remains |
| REST contract extract + compat | RestContractExtractor + rules | Same family + OpenAPI JSON/YAML `$ref` | PARTIALLY_EQUIVALENT | Unit + shared REST links | Multi-repo OpenAPI evolution MCP |
| Kafka / Avro / proto | Extractors; many UNKNOWN | fields{} + required-add BREAKING + proto numbers | PARTIALLY_EQUIVALENT | Unit rules 1.7.0 | Persisted Avro MCP fixture |
| Direct consumer impact | Routing + call match | Persisted semanticMethodImpacts | FUNCTIONALLY_EQUIVALENT | CI Java+Kotlin | — |
| Indirect / multi-hop | Producer BFS + Graphify hops | Direct edges; limitation documented | PARTIALLY_EQUIVALENT | Limitations string in MCP | Bounded hop resolver |
| Version-pinned consumers | Cache/key + coords | Explicit 1.0 impacted / 2.0 excluded | FUNCTIONALLY_EQUIVALENT | Kotlin+Java IT | — |
| Proposed change / PR diff | ChangeClassifier + base/head | Generation compare primarily | PARTIALLY_EQUIVALENT | `impact_analysis` | Add optional proposed signature inputs |
| Evidence / confidence | Rich EvidenceSource + blocking veto | Evidence strings + confidence; non-blocking | INTENTIONALLY_DIFFERENT_WITH_JUSTIFICATION | MCP `blocking:false` | Platform is advisory MCP, not review gate |
| Persistent generations | Filesystem commit indexes | PostgreSQL generations + Zoekt + vectors | PLATFORM_IMPLEMENTATION_SUPERIOR | Pipeline + CI | — |
| Team authorization | Product registry / review scope | API key + team_repository_access | INTENTIONALLY_DIFFERENT_WITH_JUSTIFICATION | Shared IT ACL | — |
| LLM semantic layer | Ollama + evidence gate | Not used for impact authority | INTENTIONALLY_DIFFERENT_WITH_JUSTIFICATION | — | Keep deterministic |
| Independent build | N/A | Must build without MultiRepo | IMPLEMENTED_UNVERIFIED | pom independence | Automated gate test |
| Comparative regression fixtures | NullUtils/DateUtils/gnostic-e2e | Money/Amount fixtures | PARTIALLY_EQUIVALENT | Shared IT | Port more ground-truth cases independently |

## Coupling confirmation

- CCP `pom.xml` has **no** `MultiRepo`, `review-agent`, or `in.wynk.ugp` dependencies.
- No Git submodule / subtree / symlink to MultiRepo.
- Comparative tests must use **independently maintained** fixtures under CCP.
