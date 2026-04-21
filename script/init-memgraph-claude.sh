# scripts/init-memgraph-claude.sh
#!/usr/bin/env bash
set -e
PROJECT="${1:?usage: init-memgraph-claude.sh <project-name>}"
TEMPLATE_URL="https://raw.githubusercontent.com/ousatov-ua/memgraph-ingester/refs/heads/main/template/CLAUDE-memgraph-template.md"
curl -s "$TEMPLATE_URL" | sed "s/{{PROJECT_NAME}}/$PROJECT/g" >> CLAUDE.md
echo "Appended Memgraph section to CLAUDE.md with project name '$PROJECT'"