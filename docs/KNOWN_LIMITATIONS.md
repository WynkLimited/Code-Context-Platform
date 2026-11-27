# Known limitations

Honest inventory of supported vs incomplete behaviour. Do not treat undocumented gaps as supported.

## Fully / strongly supported (evidence in CI)

- Zoekt text search with generation-aware publication
- Graphify → PostgreSQL graph (when enabled)
- Semantic search via local Ollama + pgvector
- Java↔Java and Kotlin JVM ABI cross-repo method impact for Maven-pinned consumers
- REST/Kafka topic linking for annotation/config-derived routes
- Soft disable, hard purge, generation retention fencing
- MCP six tools over authenticated Streamable HTTP

## Partial

- OpenAPI nested `$ref` flattening + same-repo relative file `$ref` (remote URL `$ref` rejected)
- Kafka Avro `fields{}` evolution has selected multi-repo MCP acceptance; broader consumer-linked payload coverage remains partial.
- Contract ambiguity demotion has dual-provider PostgreSQL/HTTP acceptance; broader contention remains partial.
- Proposed-change classification (signatures only; no full PR diff)
- Indirect impact via re-export hops only; method-chain multi-hop incomplete
- Webhook freshness when timestamp present; durable PG concurrency open
- Dual-key API rotation and admin status exist; organization secret distribution/revocation procedures remain operator requirements.
- Metrics/alerts YAML present; live scrape unverified
- Actual CI logical backup/restore passes; WAL/PITR and whole-environment recovery remain unverified.

## Not implemented / blocked

- Gradle dependency resolution
- OS-level compiler/Maven sandbox (cgroup/seccomp)
- Representative pilot performance certification
- Kubernetes cluster smoke / egress enforcement proof
- Actual Cursor/Claude/Windsurf client certification beyond protocol

## Intentional product differences vs MultiRepo Code Review Agent

- Advisory MCP (`blocking:false`) vs PR review gate
- Persistent generations vs ephemeral PR indexes
- Team API-key ACL vs review-product registry
- No shared module or compile/test/runtime dependency on MultiRepo

Automatic onboarding and per-repository webhook reconciliation are under implementation. GitHub App integration, lifecycle updates and genuine incremental Graphify processing are not complete.
