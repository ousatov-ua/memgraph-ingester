# scripts/init-memgraph-codex.sh
#!/usr/bin/env bash
set -e
PROJECT="${1:?usage: init-memgraph-codex.sh <project-name>}"
TEMPLATE_URL="https://raw.githubusercontent.com/ousatov-ua/memgraph-ingester/refs/heads/main/template/GEMINI-memgraph-template.md"
curl -s "$TEMPLATE_URL" | sed "s/{{PROJECT_NAME}}/$PROJECT/g" >> AGENTS.md
echo "Appended Memgraph section to AGENTS.md with project name '$PROJECT'"