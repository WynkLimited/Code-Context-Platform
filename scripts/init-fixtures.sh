#!/bin/sh
set -eu
for name in repo-library repo-orders repo-ledger; do
  target="/fixtures/$name"
  if [ ! -d "$target/.git" ]; then
    mkdir -p "$target"
    cp -R "/src/$name/." "$target/"
    git -C "$target" init --initial-branch=main
    git -C "$target" config user.name "Code Context Fixture"
    git -C "$target" config user.email "fixture@example.invalid"
    git -C "$target" add .
    git -C "$target" commit -m "Initial fixture"
  fi
done
