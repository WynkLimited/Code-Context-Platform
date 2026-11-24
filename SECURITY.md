# Security notes

## Trust boundaries

- Team API keys are parsed from `TEAM_API_KEYS=team=secret,...`; they are compared by key lookup and only the team principal is retained. Every source and semantic query joins the requesting team to `team_repository_access`.
- Repository onboarding requires a separate administrator key configured through `ADMIN_API_KEYS`; day-to-day MCP and indexing calls use team-scoped keys.
- MCP `Origin` values are checked against the exact `MCP_ALLOWED_ORIGINS` allowlist; missing `Origin` is allowed for native MCP clients. Stateless API-key authentication disables cookie CSRF; browser cross-origin access is not enabled by default.
- Webhook delivery is unsigned until an HMAC-SHA256 header validates against `WEBHOOK_SECRET`. Malformed or missing signatures are rejected before JSON parsing.
- Git URLs are restricted to HTTPS on configured `GIT_ALLOWED_HOSTS`, without URL credentials, custom ports, query strings or fragments. Local file URLs must resolve inside `LOCAL_GIT_ROOT`.
- Repository owner/name path components are constrained. Git data paths are normalized and source reads use commit trees, not arbitrary filesystem joins.
- Git and Graphify commands use argument lists without a shell and have time limits. Graphify runs in a temporary source snapshot with a scrubbed environment.
- The configured embedding model is Ollama; source and embeddings stay on the local platform network.

## Deployment requirements

- Terminate TLS at an internal ingress/reverse proxy and restrict network access to the MCP/API endpoint.
- Use high-entropy team keys, webhook secrets, GitHub App installation tokens, and database credentials from a secret manager. Do not put them in source or plain environment files on production hosts.
- Set production profile; startup rejects default webhook/database credentials or a missing Git access token/team-key configuration.
- Use read-only GitHub App permissions (`contents: read`, `metadata: read`) plus push webhooks.
- Restrict egress from the app to GitHub Enterprise/GitHub, PostgreSQL, Ollama and Zoekt; deploy the container without privileged mode and with a read-only root filesystem where writable volumes are mounted separately.
- Keep PostgreSQL, Git mirrors, Zoekt indexes, and Ollama model storage on encrypted persistent volumes with access controls and backups.

## Known security gaps to close before production

Team keys are currently held in configuration as plaintext and do not support rotation/hashed storage or per-repository scopes within a team. There is no rate limiting, audit log, key administration API, webhook delivery replay window, or Origin validator specific to MCP. Git authentication uses one installation token for all configured repositories. Graphify outbound egress is not network-enforced by application code. A security review is required before go-live.


## Verification status

Source inspection and unit tests do not constitute a security verification. In this environment Docker was unavailable and no live PostgreSQL/Zoekt/MCP stack was exercised. Cross-team authorization, SSRF, traversal, webhook replay, subprocess isolation, secret redaction, concurrency, failure recovery, and resource limits still require adversarial integration testing before production. Impact output stays non-blocking; Java call-site source snippets are returned only for repositories accessible to the authenticated team.
