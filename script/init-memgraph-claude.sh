#!/usr/bin/env bash
# scripts/init-memgraph-claude.sh
set -euo pipefail
PROJECT="${1:?usage: init-memgraph-claude.sh <project-name> [--with-memories] [--instructions-file path]}"
shift
BIN="${MEMGRAPH_INGESTER_BIN:-memgraph-ingester}"
"$BIN" --init-instructions -P "$PROJECT" --instructions-agent claude "$@"
