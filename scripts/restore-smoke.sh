#!/usr/bin/env bash
# Restore a custom-format pg_dump into RESTORE_URL and run basic readiness checks.
# Does not mutate production DATABASE_URL. Optional MCP smoke when MCP_URL + API key set.
set -euo pipefail

: "${RESTORE_URL:?RESTORE_URL is required (postgresql://… target database)}"
: "${DUMP_FILE:?DUMP_FILE is required}"

DRY_RUN="${DRY_RUN:-0}"
if [[ ! -f "$DUMP_FILE" && "$DRY_RUN" != "1" ]]; then
  echo "DUMP_FILE not found: $DUMP_FILE" >&2
  exit 1
fi

if [[ "$DRY_RUN" == "1" ]]; then
  echo "dry-run: would restore $DUMP_FILE into RESTORE_URL and run SELECT 1 / Flyway checks"
  exit 0
}

pg_restore --clean --if-exists --no-owner --no-acl --dbname="$RESTORE_URL" "$DUMP_FILE"
psql "$RESTORE_URL" -v ON_ERROR_STOP=1 -c "SELECT 1"
psql "$RESTORE_URL" -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM flyway_schema_history"
psql "$RESTORE_URL" -v ON_ERROR_STOP=1 -c "SELECT to_regclass('public.repositories') IS NOT NULL"

if [[ -n "${MCP_URL:-}" && -n "${TEAM_API_KEY:-}" ]]; then
  curl -fsS -H "X-API-Key: ${TEAM_API_KEY}" "${MCP_URL%/}/actuator/health" >/dev/null
  echo "MCP/actuator health OK"
fi

echo "restore smoke passed"
