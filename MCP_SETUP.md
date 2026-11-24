# MCP client setup

The server exposes Spring AI's Streamable HTTP MCP transport at `/mcp`.

For local Cursor, add the following to the MCP server configuration:

```json
{
  "mcpServers": {
    "code-context": {
      "url": "http://localhost:8080/mcp",
      "headers": {
        "X-API-Key": "local-dev-token"
      }
    }
  }
}
```

Use a different team key for staging/production. Supply a TLS URL and inject the key through the agent's secret store; do not commit real keys to editor settings. The HTTP MCP transport is authenticated by Spring Security's API-key filter and authorization is checked again against `team_repository_access` for each tool call.

Available tools: `search_code`, `read_file`, `find_symbol`, `get_neighbors`, `semantic_search`, and `impact_analysis`.

`impact_analysis` compares contract generations, invokes the deterministic compatibility engine, resolves team-scoped direct and bounded re-export candidates, records index freshness, and persists the result with generation/commit metadata. It returns explicit analysis statuses and `blocking: false`. Method-level consumer behavior is not analyzed, so direct and indirect candidates are not confirmed source breakages, unknown contract types remain unknown, and an empty result is not a safety verdict. PostgreSQL-backed MCP behavior has not been validated in this environment; see [IMPLEMENTATION_REPORT.md](IMPLEMENTATION_REPORT.md).
