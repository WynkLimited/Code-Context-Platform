#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
printf '%s\n' 'This removes local database, model, index, repository, and fixture volumes.'
if [ "${1:-}" != '--yes' ]; then
  printf '%s\n' 'Re-run with --yes to confirm destructive local reset.'
  exit 2
fi
docker compose down -v --remove-orphans
