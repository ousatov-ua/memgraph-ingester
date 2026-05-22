#!/usr/bin/env bash
# scripts/init-memgraph-codex.sh
set -euo pipefail
PROJECT="${1:?usage: init-memgraph-codex.sh <project-name> [--with-memories] [--instructions-file path]}"
shift
BIN="${MEMGRAPH_INGESTER_BIN:-memgraph-ingester}"
"$BIN" --init-instructions -P "$PROJECT" --instructions-agent codex "$@"
