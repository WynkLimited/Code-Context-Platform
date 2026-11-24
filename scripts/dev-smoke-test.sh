#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
base=${CODE_CONTEXT_URL:-http://localhost:8080}
curl --fail --silent --show-error "$base/actuator/health/readiness" >/dev/null
printf '%s\n' 'Application readiness passed. Run scripts/onboard-fixtures.sh for API/indexing smoke coverage.'
