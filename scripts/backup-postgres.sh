#!/usr/bin/env bash
# Consistent logical backup. Credentials are inherited via environment, never echoed.
set -euo pipefail
umask 077
: "${DATABASE_URL:?DATABASE_URL is required}"
: "${BACKUP_DIR:?BACKUP_DIR is required}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$BACKUP_DIR"
OUT_DIR="$(mktemp -d "${BACKUP_DIR%/}/${STAMP}-XXXXXX")"
DUMP_FILE="${OUT_DIR}/code_context.dump"
META_FILE="${OUT_DIR}/backup-metadata.json"
if [[ "${DRY_RUN:-0}" == "1" ]]; then
  printf '{"mode":"dry-run","dump":"code_context.dump","createdAt":"%s"}\n' "$STAMP" > "$META_FILE"
  echo "dry-run: no database connection or dump created"
  exit 0
fi
[[ -z "${DATABASE_USER:-}" ]] || export PGUSER="$DATABASE_USER"
export PGPASSWORD="${DATABASE_PASSWORD:-${PGPASSWORD:-}}"
trap 'rm -f "$DUMP_FILE" "$DUMP_FILE.sha256"' ERR
pg_dump --no-password --format=custom --no-owner --no-acl --file="$DUMP_FILE" "${DATABASE_URL#jdbc:}"
if command -v sha256sum >/dev/null; then CHECKSUM="$(sha256sum "$DUMP_FILE" | awk '{print $1}')";
else CHECKSUM="$(shasum -a 256 "$DUMP_FILE" | awk '{print $1}')"; fi
printf '%s\n' "$CHECKSUM" > "$DUMP_FILE.sha256"
printf '{"createdAt":"%s","dump":"code_context.dump","sha256":"%s","tool":"pg_dump","format":"custom"}\n' "$STAMP" "$CHECKSUM" > "$META_FILE"
echo "backup written to $OUT_DIR"
