#!/bin/sh
set -eu
for spec in 'repo-library LIBRARY' 'repo-orders SERVICE' 'repo-ledger SERVICE'; do
  set -- $spec
  name="$1"; kind="$2"
  curl --fail --silent --show-error -H 'X-API-Key: local-dev-token' -H 'Content-Type: application/json' \
    -d "{\"owner\":\"fixtures\",\"name\":\"$name\",\"cloneUrl\":\"file:///fixtures/$name\",\"defaultBranch\":\"main\",\"type\":\"$kind\"}" \
    http://localhost:8080/api/repositories
  curl --fail --silent --show-error -X POST -H 'X-API-Key: local-dev-token' \
    "http://localhost:8080/api/repositories/fixtures/$name/index"
done
