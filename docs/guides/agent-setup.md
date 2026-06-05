# Agent Setup

## Install Project Instructions

The graph becomes more useful when your agent knows how to query it. Memgraph Ingester can write a
managed instruction block into agent-specific files and replace that block on future runs.

From the ingested repository, run:

```bash
memgraph-ingester --init-instructions -P my-project
```

Choose an agent preset:

```bash
memgraph-ingester -P my-project --instructions-agent codex
memgraph-ingester -P my-project --instructions-agent claude
memgraph-ingester -P my-project --instructions-agent gemini
memgraph-ingester -P my-project --instructions-agent github
memgraph-ingester -P my-project --instructions-agent copilot
```

Include memory workflows:

```bash
memgraph-ingester -P my-project --instructions-agent codex --with-memories
```

Target a custom instruction file:

```bash
memgraph-ingester -P my-project --instructions-file .github/copilot-instructions.md
```

## Use the MCP Server

Agents should normally use
[`memgraph-ingester-mcp`](https://github.com/ousatov-ua/memgraph-ingester-mcp). It provides
project-scoped tools for code lookup, RAG discovery, call graph traversal, memory search, and memory
lifecycle updates.

Install and run it with `uvx`:

```bash
uvx memgraph-ingester-mcp
```

Common environment variables:

| Variable | Example | Purpose |
| --- | --- | --- |
| `MEMGRAPH_INGESTER_MCP_BOLT_URI` | `bolt://localhost:7687` | Memgraph Bolt URI |
| `MEMGRAPH_INGESTER_MCP_READ_ONLY` | `false` | Disable memory write tools when `true` |

## Codex Configuration

Add this to `~/.codex/config.toml`:

```toml
[mcp_servers.memgraphIngester]
command = "uvx"
args = ["memgraph-ingester-mcp"]
startup_timeout_ms = 20_000

[mcp_servers.memgraphIngester.env]
MEMGRAPH_INGESTER_MCP_BOLT_URI = "bolt://localhost:7687"
MEMGRAPH_INGESTER_MCP_READ_ONLY = "false"
```

Verify:

```bash
codex mcp list
```

## Claude Configuration

Minimal `.claude.json`:

```json
{
  "mcpServers": {
    "memgraphIngester": {
      "type": "stdio",
      "command": "uvx",
      "args": ["memgraph-ingester-mcp"],
      "env": {
        "MEMGRAPH_INGESTER_MCP_BOLT_URI": "bolt://localhost:7687",
        "MEMGRAPH_INGESTER_MCP_READ_ONLY": "false"
      }
    }
  }
}
```

Verify:

```bash
claude mcp list
```

## Gemini Configuration

Add this to `~/.gemini/settings.json`:

```json
{
  "mcpServers": {
    "memgraphIngester": {
      "command": "uvx",
      "args": ["memgraph-ingester-mcp"],
      "env": {
        "MEMGRAPH_INGESTER_MCP_BOLT_URI": "bolt://localhost:7687",
        "MEMGRAPH_INGESTER_MCP_READ_ONLY": "false"
      },
      "timeout": 5000,
      "trust": false
    }
  }
}
```

Verify:

```bash
gemini mcp list
```

## GitHub Copilot Configuration

Add this to `~/.copilot/mcp-config.json`:

```json
{
  "mcpServers": {
    "memgraphIngester": {
      "type": "stdio",
      "command": "uvx",
      "args": ["memgraph-ingester-mcp"],
      "env": {
        "MEMGRAPH_INGESTER_MCP_BOLT_URI": "bolt://localhost:7687",
        "MEMGRAPH_INGESTER_MCP_READ_ONLY": "false"
      }
    }
  }
}
```

## OpenCode Configuration

Add this to `~/.config/opencode/opencode.jsonc`:

```json
{
  "mcp": {
    "memgraphIngester": {
      "type": "local",
      "command": ["uvx", "memgraph-ingester-mcp"],
      "enabled": true,
      "environment": {
        "MEMGRAPH_INGESTER_MCP_BOLT_URI": "bolt://localhost:7687",
        "MEMGRAPH_INGESTER_MCP_READ_ONLY": "false"
      }
    }
  }
}
```

## Read-Only Mode

Set `MEMGRAPH_INGESTER_MCP_READ_ONLY` to `"true"` when the agent should only inspect code and
memory. Keep it `"false"` when you want the agent to create, update, or close Memory records.

## Raw Cypher Fallback

If the MCP server is not available, generate raw Memgraph/Cypher guidance:

```bash
memgraph-ingester --init-instructions -P my-project --no-memgraph-ingester-mcp
```

Use this mode when an agent can read instructions but cannot call `memgraph-ingester-mcp` tools.
Without MCP, the user or agent will also need [`mgconsole`](https://memgraph.com/docs/querying/overview)
or another Bolt/Cypher client to query Memgraph directly.
