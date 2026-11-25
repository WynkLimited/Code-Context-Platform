# Release checklist

Use with `docs/PRODUCTION_READINESS_LEDGER.md`. Do not ship while the ledger decision is **NOT READY FOR PRODUCTION**.

## Engineering gates

- [ ] Mandatory CI green on exact release SHA (unit + SharedLibrary + Graphify + retention + least-privilege)
- [ ] Architectural independence test green (no MultiRepo coupling)
- [ ] All six MCP tools verified on Streamable HTTP for the candidate build
- [ ] Cross-repo Java/Kotlin impact acceptance green
- [ ] No critical/high vulnerabilities without approved exceptions
- [ ] Migrations apply on PostgreSQL 17 + pgvector fresh and upgrade paths

## External / ops gates (currently blocking)

- [ ] OS sandbox approval and drill for compiler/Maven/Graphify
- [ ] PostgreSQL backup restore + PITR drill in isolated environment
- [ ] Kubernetes smoke + NetworkPolicy egress proof
- [ ] Pilot performance ≤1 min searchable under agreed footprint
- [ ] Organizational security review
- [ ] Pilot team validation on internal repositories

## Docs

- [ ] Ledger statuses match evidence SHAs
- [ ] KNOWN_LIMITATIONS accurate
- [ ] SECURITY_ASSESSMENT residual risks reviewed
- [ ] OPERATIONS / DR runbooks have executed commands (not scripts-only)
