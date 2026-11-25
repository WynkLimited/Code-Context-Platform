#!/usr/bin/env bash
# Restore only to an explicitly supplied isolated target. Verify integrity before database changes.
set -euo pipefail
: "${RESTORE_URL:?RESTORE_URL is required}"
: "${DUMP_FILE:?DUMP_FILE is required}"
if [[ "${DRY_RUN:-0}" == "1" ]]; then
  echo "dry-run: no restore or database connection performed"
  exit 0
fi
[[ -f "$DUMP_FILE" ]] || { echo "dump file not found" >&2; exit 1; }
EXPECTED="${EXPECTED_SHA256:-}"
if [[ -z "$EXPECTED" && -f "$DUMP_FILE.sha256" ]]; then EXPECTED="$(cat "$DUMP_FILE.sha256")"; fi
[[ "$EXPECTED" =~ ^[[:xdigit:]]{64}$ ]] || { echo "trusted SHA-256 sidecar or EXPECTED_SHA256 is required" >&2; exit 1; }
if command -v sha256sum >/dev/null; then ACTUAL="$(sha256sum "$DUMP_FILE" | awk '{print $1}')";
else ACTUAL="$(shasum -a 256 "$DUMP_FILE" | awk '{print $1}')"; fi
[[ "$ACTUAL" == "$EXPECTED" ]] || { echo "backup checksum mismatch; restore refused" >&2; exit 1; }
[[ -z "${RESTORE_USER:-}" ]] || export PGUSER="$RESTORE_USER"
export PGPASSWORD="${RESTORE_PASSWORD:-${PGPASSWORD:-}}"
TARGET="${RESTORE_URL#jdbc:}"
pg_restore --list "$DUMP_FILE" >/dev/null
pg_restore --no-password --exit-on-error --single-transaction --clean --if-exists --no-owner --no-acl --dbname="$TARGET" "$DUMP_FILE"
psql --no-password "$TARGET" -v ON_ERROR_STOP=1 <<'SQL'
DO $$ BEGIN
  IF to_regclass('public.repositories') IS NULL OR to_regclass('public.flyway_schema_history') IS NULL THEN
    RAISE EXCEPTION 'restored application tables missing';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname='vector') THEN
    RAISE EXCEPTION 'restored pgvector extension missing';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE success) OR
     EXISTS (SELECT 1 FROM flyway_schema_history WHERE NOT success) THEN
    RAISE EXCEPTION 'restored Flyway history invalid';
  END IF;
END $$;
SQL
if [[ -n "${MCP_URL:-}" && -n "${TEAM_API_KEY:-}" ]]; then
  curl -fsS -H "X-API-Key: ${TEAM_API_KEY}" "${MCP_URL%/}/actuator/health" >/dev/null
  echo "application health OK (HTTP MCP tool replay is a separate check)"
fi
echo "restore smoke passed"
