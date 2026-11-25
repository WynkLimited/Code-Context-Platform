# Performance benchmark report

**Status: NOT production acceptance** — representative pilot benchmarks remain open.

## Target (from product design)

- Pilot footprint ~4–10 repos, 8 vCPU, 32 GB RAM, ~250 GB SSD
- Push becomes searchable ≈ within 1 minute under agreed pilot workload
- High-confidence cross-repo link correctness ≥ 90% against ground truth

## Measured so far

| Workload | Environment | Result |
|---|---|---|
| SharedLibraryImpactPostgresIT (fixture set) | GitHub Actions CI services | Functional pass; not a capacity study |
| Unit suite (~226 tests) | Local Temurin 21 | Pass; not latency evidence |

## Remaining work

1. Define fixture corpus size (LOC / repos) matching pilot  
2. Capture p50/p95/p99 for Zoekt, graph, semantic, impact_analysis  
3. Record indexing push-to-searchable and push-to-all-context-ready  
4. Compare only when environments match reference MultiRepo runs  

Do not extrapolate production capacity from synthetic fixtures alone.
