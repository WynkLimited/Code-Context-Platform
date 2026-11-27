# Operations runbook

**Status: PARTIAL** — commands are validated where noted; cluster-specific paths need operator fill-in.

## Health

```bash
curl -fsS "$BASE_URL/actuator/health"
curl -fsS "$BASE_URL/actuator/prometheus" | head
```

## Indexing stuck / queue depth

1. Check `index_jobs` for `QUEUED`/`RUNNING` age and `error`.
2. Check `repository_index_leases` for expired leases; workers reclaim on heartbeat expiry.
3. Check `contract_refresh_jobs` backlog.
4. Metrics: `code_context_indexing_*`, MCP error rate from `deploy/prometheus/alerts.yaml`.

## Zoekt degraded

1. Confirm Zoekt webserver health.
2. Reconcile orphan shards (generation retention outbox + Zoekt cleaner).
3. Re-index affected repositories via `/api/repositories/{owner}/{name}/index` with team auth.

## Ollama / semantic search degraded

1. `curl $OLLAMA_BASE_URL/api/tags` — confirm embedding model present.
2. Readiness should reflect dependency health; MCP `semantic_search` returns structured errors when model fails.

## API key rotation

1. Set `TEAM_API_KEYS=team=new|old` (dual-key).
2. Roll clients to `new`.
3. Drop `|old` to revoke.

## Escalation

- Persistent indexing failures after retry exhaustion → platform on-call.
- Suspected cross-team leakage → security on-call; rotate keys immediately.
