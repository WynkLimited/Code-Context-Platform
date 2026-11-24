#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
docker compose up --build -d
docker compose ps
./scripts/dev-smoke-test.sh
