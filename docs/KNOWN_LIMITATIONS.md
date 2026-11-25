# Known limitations

Honest boundaries of the Code Context Platform as of the production-readiness closure on branch `fix/jackson3-postgres-acceptance`.

## Analysis

- Kotlin **source** compatibility (inline, suspend, defaults, nullability, exact source↔JVM mapping) remains **UNKNOWN**; JVM binary ABI removals are reported.
- Full indirect method-chain / multi-hop blast radius is **not** complete; MCP limitations state this explicitly.
- Gradle dependency graphs are **not** implemented (Maven-first).
- Dynamic URLs/topics without resolvable configuration stay unresolved/ambiguous with calibrated confidence — never fabricated.
- `impact_analysis` is **informational** (`blocking:false`); it is not a merge gate.

## Security / execution

- Compiler isolation is application-level (env allowlist, path containment, timeouts, output bounds). OS cgroup/seccomp sandbox is **not** claimed.
- In-process rate limiting defaults to off (`rate-limit-per-minute:0`); multi-replica shared limiter is not implemented.

## Operations

- Backup/restore scripts exist; **PITR and post-restore MCP smoke** require an isolated PostgreSQL environment that has not been drilled in CI.
- Kubernetes manifests exist; cluster egress/smoke and live Prometheus alert firing are not verified here.
- Pilot “push searchable within ~1 minute” needs representative repository benchmarks beyond fixture sizes.

## Reference alignment

- MultiRepo-Code-Review-Agent is an engineering baseline only. No shared module or dependency is permitted.
- Comparative accuracy against MultiRepo must use independently maintained fixtures and ground truth — not MultiRepo output as oracle.
