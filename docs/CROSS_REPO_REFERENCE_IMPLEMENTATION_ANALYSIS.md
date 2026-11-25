# Cross-repository reference implementation analysis

Read-only study of `WynkLimited/MultiRepo-Code-Review-Agent` for engineering alignment with Code Context Platform (CCP).

| Field | Value |
|---|---|
| Reference remote | https://github.com/WynkLimited/MultiRepo-Code-Review-Agent |
| Branch | `main` |
| Commit SHA | `65144c6060e97c491546674586ea8ae5020588c7` |
| Inspected | 2026-11-25 |
| Coupling | **None.** CCP must not depend on this repository at compile, test, or runtime. |

## Product distinction

| | MultiRepo Code Review Agent | Code Context Platform |
|---|---|---|
| Purpose | PR review: classify changes, find consumers, optionally block | Continuous indexed intelligence served via MCP |
| Trigger | Pull-request / review job | Onboarding, webhooks, durable indexing jobs |
| Persistence | Per-commit filesystem contract indexes + analysis cache | PostgreSQL generations, Zoekt, pgvector, durable links |
| Authority | Deterministic contract rules + evidence gate; LLM advisory | Compatibility engine + persisted semantic edges; informational (`blocking:false`) |

## Adopted engineering approaches (concepts only)

1. Separate **routing** (who might care) from **breakage proof** (who is evidenced to use the changed surface).
2. Immutable, versioned contract indexes and compatibility rules versions.
3. Deterministic BREAKING vs UNKNOWN; never invent relationships.
4. Version-aware consumers (pinned baseline vs head).
5. Bounded analysis with explicit incompleteness / truncation flags.
6. Evidence taxonomy and calibrated confidence.

## Explicitly not copied

- Shared Maven/Gradle module or Git submodule
- LLM blocking gate as production authority
- Filesystem-only contract store as primary persistence
- PR-only execution model

## Reference limitations (do not reproduce as CCP “features”)

- Full Maven/Gradle mediation is not production blocking authority in MultiRepo.
- Kotlin production semantic gate historically NOT READY; CCP has compiler JVM ABI path with UNKNOWN source-compat.
- Kafka/REST signature changes often UNKNOWN without schema proof.
- Transitive helper→export hops are advisory and truncated.

See `docs/CROSS_REPO_IMPACT_PARITY_MATRIX.md` for capability-by-capability status.
