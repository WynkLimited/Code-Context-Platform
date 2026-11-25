# Security assessment

**Status: PARTIAL — NOT a production security sign-off**

## Authenticated surfaces

- MCP Streamable HTTP requires API keys; dual-key rotation (`team=current|previous`) unit-verified.
- Cross-team isolation exercised for all six MCP tools in `SharedLibraryImpactPostgresIT` (CI).
- Webhook HMAC + delivery-id replay + optional `head_commit.timestamp` freshness gate.

## Network / SSRF

- Git: allowlisted HTTPS hosts, DNS rebinding / metadata rejection, `http.followRedirects=false` on mirrors.
- Maven: only operator-configured repositories; POM-declared remotes ignored; HTTPS + `OutboundUrlGuard`.
- Residual: Maven Resolver HTTP transport still follows redirects; cluster NetworkPolicy must deny private egress (documented limitation).

## Execution sandbox

- App-level path/symlink guards and process timeouts exist.
- OS-level cgroup/seccomp sandbox for kotlinc/Maven/Graphify: **BLOCKED** pending host capability.

## Secrets

- No credentials in repository; webhook/API keys via env; audit redaction patterns present.
- External AI agents may still transmit retrieved source outside the org depending on client config — document in MCP integration guide.

## Residual risks (release-blocking or approval-gated)

1. OS sandbox for untrusted build/compiler subprocesses.
2. Maven redirect follow to unexpected hosts without egress policy.
3. Prompt-injection via retrieved source into agent models (inherent agent risk).
4. Admin key lifecycle API incomplete.
