# Local development

Requirements: Docker Compose v2, at least 8 CPU cores, 24 GB RAM recommended for the embedding model, and 25 GB free disk for containers and model data.

```sh
docker compose up --build -d
docker compose ps
```

Convenience scripts wrap the Compose lifecycle: `scripts/dev-up.sh`, `scripts/dev-status.sh`, `scripts/dev-down.sh`, `scripts/dev-reset.sh --yes`, and `scripts/dev-smoke-test.sh`. Reset removes all named local data volumes. To onboard the included fixture repositories and queue indexing, run `scripts/onboard-fixtures.sh` after readiness.

The first start pulls the pinned Qwen embedding model and initializes three fixture repositories. Wait for `app`, `postgres`, `ollama-model-init`, `zoekt`, and `fixture-init` to become healthy/completed. Bootstrap the fixtures and queue their first index:

```sh
./scripts/onboard-fixtures.sh
```

Use the returned job IDs with `GET /api/index-jobs/{id}` and wait for `SUCCEEDED`. The library fixture exports `com.acme.common.Money`; the orders fixture imports it, calls a REST path, and publishes a Kafka topic; the ledger fixture provides that REST route and consumes the topic. Connect Cursor using `MCP_SETUP.md` and call the six tools.

Useful commands:

```sh
docker compose logs -f app
docker compose exec postgres psql -U code_context_app -d code_context -c 'select owner,name from repositories;'
docker compose down                 # keep named volumes
docker compose down -v              # delete all local platform data
```

Copy `.env.example` to `.env` only when changing local settings. Local default credentials are for loopback development only; the local key has onboarding administrator access. Production uses separate administrator and team keys. The app rejects non-HTTPS remote URLs except file URLs under `LOCAL_GIT_ROOT`; set `GIT_ALLOWED_HOSTS` for an approved GitHub Enterprise host. Provide `GIT_ACCESS_TOKEN` through an environment variable for private GitHub App access; it is never written into the repository row.

Graphify builds in a temporary copy of the committed source tree. Its child process receives a scrubbed environment with the local Ollama endpoint, not Git credentials or API keys. Search index metadata and database records are published by generation; incomplete DB generations remain unpublished.

## Current local acceptance limits

The Compose topology and fixture flow are provided, but the Docker daemon was unavailable in this environment, so the full Compose end-to-end sequence was not executed. Maven dependencies were resolved and the full unit suite passed using the temporary JDK 21 installation. Ollama responds locally; application-level embedding persistence/retrieval was not verified. `impact_analysis` now includes Java AST call-site candidates for matched source files, with unresolved parsing explicit and no behavioral or blocking conclusion.
