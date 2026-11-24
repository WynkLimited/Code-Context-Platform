# Indexing pipeline

1. Onboarding validates the owner/name identity, stores the HTTPS Git URL and grants the authenticated team access. A local `file://` URL is accepted only when it resolves under `LOCAL_GIT_ROOT` and contains a Git repository.
2. A GitHub push webhook verifies `X-Hub-Signature-256` using constant-time HMAC comparison and inserts a unique delivery key into `index_jobs`. Jobs can be inspected and manually queued through REST.
3. A worker claims one durable queue row with `FOR UPDATE SKIP LOCKED`. Stale `RUNNING` rows are requeued once and eventually failed. It syncs the default branch, records the actual fetched commit, and reads tracked blobs from Git.
4. It filters generated/build/vendor/binary/oversize content, stores per-path blob hashes, and reuses unchanged nodes/contracts/vectors from the prior generation. Changed files receive Java structure extraction, stable SHA-256 chunk hashes, and local Ollama embeddings.
5. The current adapters record evidence-bearing REST, Kafka, Maven, Java import/type and protobuf contracts. Matching exports/imports across published generations create contract links with the lower evidence confidence.
6. Optional Graphify output is generated in a temporary materialized source tree and imported into PostgreSQL. Zoekt builds an incremental shard with a generation-specific repository name.
7. Only after indexing stages succeed does a short database transaction swap the published generation and mark the job successful. A failed generation stays unpublished.

The existing implementation has important missing stages relative to the reference: strict stale-index/freshness policy, dependency-aware rechecks of unresolved imports, compatibility analysis, confidence/provenance vetoes, advanced Java/Kotlin symbol binding, robust deletion/rename graph semantics, durable retry backoff, and graph/vector retention. These are release blockers for treating impact output as complete.
