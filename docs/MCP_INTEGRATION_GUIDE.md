# MCP integration guide

## Endpoint

- Protocol: Streamable HTTP MCP (`spring.ai.mcp.server.protocol=STREAMABLE`)
- Path: `/mcp` (configurable via `spring.ai.mcp.server.streamable-http.mcp-endpoint`)
- Auth: `Authorization: Bearer <team-api-key>` (or configured API-key header)

## Tools

| Tool | Purpose |
|---|---|
| `search_code` | Exact/regex Zoekt search across authorized repositories |
| `read_file` | UTF-8 file content at published commit (optional line range) |
| `find_symbol` | Symbol definitions with file/line evidence |
| `get_neighbors` | Direct callers/callees with evidence |
| `semantic_search` | Local Ollama embedding + pgvector similarity |
| `impact_analysis` | Contract generation compare + semantic method impacts; optional `oldSignature`/`newSignature` |

## Agent configuration notes

- Keep `mcp-allowed-origins` tight for browser-based clients.
- Dual-key rotation: configure `team=current\|previous` during overlap; drop previous to revoke.
- Self-hosted indexing/embeddings do **not** prevent an external agent model backend from receiving retrieved source. Prefer org-approved agent backends.

## Compatibility status

| Client | Status |
|---|---|
| Protocol (Streamable HTTP) | Verified in SharedLibraryImpactPostgresIT |
| Cursor / Claude Code / Windsurf / Copilot | IMPLEMENTED_UNVERIFIED / BLOCKED without local client installs |

## Example Cursor MCP snippet

```json
{
  "mcpServers": {
    "code-context": {
      "url": "https://code-context.example/mcp",
      "headers": {
        "Authorization": "Bearer ${CODE_CONTEXT_API_KEY}"
      }
    }
  }
}
```
