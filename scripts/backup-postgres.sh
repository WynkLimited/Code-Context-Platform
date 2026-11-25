#!/usr/bin/env bash
# Logical backup of the Code Context Platform PostgreSQL database.
# Requires pg_dump on PATH. Secrets must come from the environment; never log them.
set -euo pipefail

: "${DATABASE_URL:?DATABASE_URL is required (jdbc:postgresql://host:port/db or postgresql://…)}"
: "${BACKUP_DIR:?BACKUP_DIR is required}"

DRY_RUN="${DRY_RUN:-0}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="${BACKUP_DIR%/}/${STAMP}"
DUMP_FILE="${OUT_DIR}/code_context.dump"
META_FILE="${OUT_DIR}/backup-metadata.json"

normalize_url() {
  local url="$1"
  if [[ "$url" == jdbc:postgresql:* ]]; then
    url="${url#jdbc:}"
  fi
  printf '%s' "$url"
}

PG_URL="$(normalize_url "$DATABASE_URL")"

mkdir -p "$OUT_DIR"
if [[ "$DRY_RUN" == "1" ]]; then
  printf '{"mode":"dry-run","dump":"%s","createdAt":"%s"}\n' "$DUMP_FILE" "$STAMP" >"$META_FILE"
  echo "dry-run: would write $DUMP_FILE"
  exit 0
}

PGPASSWORD="${DATABASE_PASSWORD:-}" pg_dump --format=custom --no-owner --no-acl --file="$DUMP_FILE" "$PG_URL"
CHECKSUM="$(shasum -a 256 "$DUMP_FILE" | awk '{print $1}')"
cat >"$META_FILE" <<EOF
{
  "createdAt": "${STAMP}",
  "dump": "$(basename "$DUMP_FILE")",
  "sha256": "${CHECKSUM}",
  "tool": "pg_dump",
  "format": "custom"
}
EOF
echo "backup written to $OUT_DIR"
