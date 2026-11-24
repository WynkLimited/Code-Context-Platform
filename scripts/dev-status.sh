#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
docker compose ps
printf '\n%s\n' 'Application readiness:'
curl --fail --silent --show-error http://localhost:8080/actuator/health/readiness
printf '\n'
