# Troubleshooting

- **Maven parent cannot resolve**: verify access to Maven Central, a configured corporate mirror, and the Maven cache directory. `mvn` will not build if `spring-boot-starter-parent:4.0.10` cannot download.
- **App fails to start on Flyway**: confirm PostgreSQL is healthy and pgvector extension is installed/allowed. `EMBEDDING_DIMENSIONS` is a schema-time setting; do not change it on an existing database without a migration.
- **Embedding dimension mismatch**: check Ollama reports `qwen3-embedding:0.6b` and `EMBEDDING_DIMENSIONS=1024`; every generated vector is checked against the configured dimension.
- **Zoekt unavailable**: verify the `/api/search` RPC endpoint, shared `/data` volume, `-rpc` server flag, indexer binary and app write permissions.
- **Graphify failed**: use pinned `graphifyy==0.9.118`; inspect the job error. The initial CLI command is AST update with clustering disabled. Graphify output format changes require adapter tests.
- **Webhook rejected**: calculate HMAC over the exact raw request bytes, use `sha256=<hex>`, and verify the same secret is configured.
- **Repository access denied**: the team key maps to one team; onboarding writes its repository grant. A team cannot see a repository merely because it knows the repository name.
- **No impact links**: ensure both provider and consumer generations are published and inspect their contract rows. Current literal extractors have limited language/config coverage and do not prove compatibility.
