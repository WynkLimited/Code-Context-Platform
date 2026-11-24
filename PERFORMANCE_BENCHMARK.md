# Performance benchmark status

No performance benchmark has been run against this environment. The Docker daemon is unavailable, and the local application, PostgreSQL, and Zoekt services were not running. The approximately one-minute update target is therefore **unverified**.

## Required benchmark

Run the same pinned fixtures/build in a disposable Compose environment and record CPU model/count, memory limit, storage type, Java version, model version, repository commit and warm/cold state. Use at least 4–10 repositories, including one representative large repository. Capture p50/p95/p99 and peak RSS for Git sync, Graphify parse/import, contract extraction, symbol binding, embedding, Zoekt update, PostgreSQL publication, exact/regex/semantic search and impact analysis. Repeat with concurrent updates at configured worker limits and failure injection for Ollama and Zoekt. Preserve source counts and output hashes so throughput comparisons do not hide reduced analysis coverage.

## Current measurements

| Operation | Result |
|---|---|
| Repository indexing | Not measured |
| Contract extraction / binding | Not measured |
| Embedding / vector search | Not measured |
| Zoekt search/update | Not measured |
| Cross-repository impact | Not measured |
| Memory / concurrency / freshness | Not measured |

Do not size production capacity or claim one-minute freshness from unit-test timings. Pilot with production-like repository sizes before enabling broad indexing.
