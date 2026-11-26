# Disaster recovery runbook

**Status: PARTIAL / BLOCKED for production sign-off** until WAL/PITR and whole-environment recovery drills execute in an isolated environment. Actual CI logical backup/restore and full-application process-death recovery already pass; they do not certify PITR or production RPO/RTO.

## Components to recover

| Component | Rebuild path |
|---|---|
| PostgreSQL | `scripts/backup-postgres.sh` / restore; Flyway re-apply if schema-only |
| Git mirrors | Re-sync from origin for onboarded repos |
| Zoekt | Re-index from published generations |
| Graph / contracts / embeddings | Re-index jobs reconstruct from mirrors |

## Proposed targets (pending org approval)

- RPO: ≤ 24h for PostgreSQL (daily backup + WAL if PITR enabled)
- RTO: ≤ 4h for pilot footprint after infrastructure restore

## Drill checklist (must execute before VERIFIED)

1. [ ] Take base backup in isolated env  
2. [ ] Inject row-level change; capture WAL position  
3. [ ] Restore backup; verify repository registrations  
4. [ ] PITR to pre-injection point  
5. [ ] Re-index smoke; MCP six-tool smoke  
6. [ ] Record actual RTO/RPO in ledger  

Scripts alone are not acceptance.
