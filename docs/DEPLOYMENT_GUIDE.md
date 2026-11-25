# Deployment guide

## Runtime versions (approved)

- Java 21, Spring Boot 4, Spring AI 2.x
- PostgreSQL 17 + pgvector
- Zoekt, Graphify (optional), Ollama + Qwen3-Embedding
- Git

## Compose / K8s

- Compose and `deploy/k8s` manifests exist for pilot topology.
- Pin image digests in production; non-root where applicable.
- Inject secrets via env (`TEAM_API_KEYS`, `WEBHOOK_SECRET`, `DATABASE_*`, `GIT_ACCESS_TOKEN`).
- Maven: set `MAVEN_ALLOWED_REPOSITORIES` / `MAVEN_ALLOWED_HOSTS`; deny private egress via NetworkPolicy (Maven HTTP redirects cannot be fully disabled in-resolver).

## Independent deployment acceptance

Build and deploy using **only** this repository. MultiRepo-Code-Review-Agent must not be present on the classpath, in CI caches as a product dependency, or at runtime.

```bash
mvn -B verify   # or CI workflow_dispatch on the release SHA
```

## Smoke

1. Health/readiness green  
2. Onboard fixture library + consumer  
3. Await index success  
4. Call all six MCP tools with team API key  

Cluster smoke remains BLOCKED without a target cluster.
