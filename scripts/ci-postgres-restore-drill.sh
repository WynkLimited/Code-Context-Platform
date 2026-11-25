#!/usr/bin/env bash
# Disposable CI-service drill only: never accepts an operator database or host override.
set -euo pipefail
[[ "${CI:-}" == "true" ]] || { echo "this drill is restricted to the disposable CI service" >&2; exit 1; }
export PGHOST=127.0.0.1 PGPORT=5432 PGUSER="${DATABASE_USER:?}" PGPASSWORD="${DATABASE_PASSWORD:?}"
SOURCE=code_context_test
TARGET=code_context_restore_test
SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d /tmp/ccp-restore-drill-XXXXXX)"
DRILL_STARTED="$(date +%s)"
CREATED_TARGET=0
CREATED_SCHEMA=0
cleanup() {
  if [[ "$CREATED_SCHEMA" == 1 ]]; then psql --no-password -d "$SOURCE" -v ON_ERROR_STOP=1 -qc 'DROP SCHEMA IF EXISTS restore_drill CASCADE' >/dev/null; fi
  if [[ "$CREATED_TARGET" == 1 ]]; then dropdb --no-password --if-exists "$TARGET"; fi
  rm -rf "$WORK"
}
trap cleanup EXIT
[[ "$(psql --no-password -d "$SOURCE" -Atqc 'SHOW server_version_num')" == 17* ]] || { echo "PostgreSQL 17 required" >&2; exit 1; }
psql --no-password -d "$SOURCE" -v ON_ERROR_STOP=1 -qc 'CREATE SCHEMA restore_drill'
CREATED_SCHEMA=1
psql --no-password -d "$SOURCE" -v ON_ERROR_STOP=1 -q <<'SQL'
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM repositories) OR NOT EXISTS (SELECT 1 FROM semantic_call_edges) OR
     NOT EXISTS (SELECT 1 FROM cross_repo_impact_analyses) THEN
    RAISE EXCEPTION 'drill requires populated acceptance repository, semantic and impact data';
  END IF;
END $$;
CREATE TABLE restore_drill.witness(id integer PRIMARY KEY, payload jsonb, embedding vector(3));
INSERT INTO restore_drill.witness VALUES (1,'{"phase":"before-backup"}','[1,2,3]');
SQL
fingerprint() {
  psql --no-password -d "$1" -qAt -F '|' -v ON_ERROR_STOP=1 <<'SQL'
SELECT format('SELECT %L, count(*), md5(coalesce(string_agg(row_to_json(t)::text, E''\n'' ORDER BY row_to_json(t)::text), '''')) FROM %I.%I t;',
              schemaname || '.' || tablename, schemaname, tablename)
FROM pg_tables WHERE schemaname IN ('public','restore_drill') ORDER BY schemaname,tablename
\gexec
SQL
}
fingerprint "$SOURCE" > "$WORK/expected"
DATABASE_URL="postgresql://127.0.0.1:5432/$SOURCE" BACKUP_DIR="$WORK/backups" bash "$SCRIPTS/backup-postgres.sh"
DUMP_FILE="$(find "$WORK/backups" -name code_context.dump -type f)"
[[ -f "$DUMP_FILE" ]] || { echo "backup artifact missing" >&2; exit 1; }
psql --no-password -d "$SOURCE" -v ON_ERROR_STOP=1 -qc "UPDATE restore_drill.witness SET payload='[\"after-backup\"]' WHERE id=1"
fingerprint "$SOURCE" > "$WORK/modified"
if cmp -s "$WORK/expected" "$WORK/modified"; then echo "source mutation was not observed" >&2; exit 1; fi
createdb --no-password "$TARGET"
CREATED_TARGET=1
RESTORE_URL="postgresql://127.0.0.1:5432/$TARGET" DUMP_FILE="$DUMP_FILE" bash "$SCRIPTS/restore-smoke.sh"
fingerprint "$TARGET" > "$WORK/actual"
diff -u "$WORK/expected" "$WORK/actual"
cat "$WORK/actual"
echo "PASS: PostgreSQL 17 logical backup/restore preserved all public tables, Flyway, pgvector and the pre-mutation witness."
echo "Scope: disposable CI fixture; not WAL/PITR, production RPO/RTO, volume recovery or post-restore HTTP MCP."
echo "Fixture drill elapsed seconds: $(( $(date +%s) - DRILL_STARTED )) (not a production RTO)."
