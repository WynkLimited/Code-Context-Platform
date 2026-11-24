# Architecture

```text
GitHub push -- HMAC --> durable PostgreSQL job queue
                            |
                            v
            JGit clone/sync --> committed Git tree
                |               |                 |
                |               |                 +--> Zoekt generation index
                |               +--> Graphify + JavaParser --> code_nodes/code_edges
                +--> contract extraction + chunking --> contracts/code_chunks/pgvector
                            |
                            v
        atomic PostgreSQL generation publication
                            |
           Spring AI MCP Streamable HTTP + REST
```

The repository clone and Git object database remain the source for complete source retrieval. On reads, the platform resolves the current published commit and reads the blob from that exact commit. PostgreSQL contains team grants, queue state, contract evidence, graph nodes/edges, chunk text and 1,024-dimensional vectors. Zoekt handles exact/regex text queries; Ollama generates both stored and query embeddings. The app does not call a remote LLM.

Each job indexes one current default-branch commit. `generation_files` stores blob SHA per path. For an unchanged path, code nodes, extracted contracts and existing vectors are copied to the new generation; changed and new paths are parsed/re-embedded; deleted paths are omitted. Zoekt uses incremental indexing against the Git tree and generation-specific repository metadata. The database's previous generation remains visible until the new generation's DB publication transaction commits. MCP search filters each query using the current published generation's Zoekt repository name.

Graphify's output is imported from `nodes` and `links`. The graph import stores original JSON as node/edge evidence. JavaParser contributes Java class/method chunks. The contract extractor is presently an initial adapter: REST literal routes, Kafka literal topics, Java imports/classes, Maven coordinates, and protobuf service/RPC declarations. See `REFERENCE_PORT_MAP.md` for source fidelity and missing conformance areas.

## Decisions and deviations

- Boot 4.0.10 / Spring AI 2.0.2 are selected because they are stable releases for this project date and expose Streamable HTTP MCP and pgvector support. The target repo had no code or dependency versions to preserve.
- The reference codebase uses Java 17 and Quarkus; this new service follows the attached Java 21/Spring architecture and does not depend on it.
- JGit provides structured Git operations, avoiding shell interpolation. The Zoekt/Graphify CLIs are invoked with argument arrays, timeouts, and sanitized inputs.
- Generation-specific Zoekt names preserve the old search index if a new DB generation fails. Old Zoekt shards are not yet garbage-collected.
- The PostgreSQL schema is versioned through Flyway and uses one relational/vector database rather than adding a graph database.
