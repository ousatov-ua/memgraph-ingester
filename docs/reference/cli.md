# CLI Reference

## Required Options

| Option | Short | Description |
| --- | --- | --- |
| `--source` | `-s` | Root directory to scan |
| `--bolt` | `-b` | Memgraph Bolt URL, for example `bolt://localhost:7687` |
| `--project` | `-P` | Logical project namespace for graph nodes |

## Common Options

| Option | Default | Description |
| --- | --- | --- |
| `--apply-schema` | `false` | Apply Memgraph constraints and indexes before ingestion |
| `--wipe-project-code` | `false` | Delete this project's code graph before ingesting |
| `--wipe-project-memories` | `false` | Delete this project's memory graph before ingesting |
| `--watch` | `false` | Watch the source tree and re-ingest changed files |
| `--threads` | `1` | Parser threads feeding one serialized Memgraph writer; 4 to 8 is usually useful for large projects |
| `--with-memories` | `false` | Include Memory workflow instructions and refresh MemoryChunk rows |
| `--init-instructions` | `false` | Write or replace managed agent instructions and exit |
| `--instructions-agent` | `codex` | Agent preset: `codex`, `claude`, `gemini`, `github`, or `copilot` |
| `--instructions-file` | preset file | Instruction file to update |
| `--no-mcp` | `false` | Install `mgtools` CLI instruction templates for agents without MCP access; implies `--init-instructions` |
| `--help` | | Print CLI help |
| `--version` | | Print CLI version |

## Runtime Options

| Option | Default | Description |
| --- | --- | --- |
| `--js-runtime-mode` | `managed` | `managed`, `system`, or `offline` |
| `--js-runtime-cache` | `~/.cache/memgraph-ingester` | Cache for managed Node.js and TypeScript downloads |
| `--check-js-runtime` | `false` | Run a JS parser runtime smoke check without connecting to Memgraph |
| `--python-runtime-mode` | `managed` | `managed`, `system`, or `offline` |
| `--python-runtime-cache` | `~/.cache/memgraph-ingester` | Cache for managed CPython downloads and private venvs |
| `--check-python-runtime` | `false` | Run a Python parser runtime smoke check without connecting to Memgraph |
| `--ctags-runtime-mode` | `managed` | `managed`, `system`, or `offline` |
| `--check-ctags-runtime` | `false` | Run a ctags runtime smoke check without connecting to Memgraph |

## Exit Codes

| Code | Meaning |
| ---: | --- |
| `0` | Success |
| `1` | Invalid arguments or runtime setup failure |
| `2` | One or more files failed to parse or ingest |

## More Detail

The project README remains the exhaustive command reference:
[README.md](https://github.com/ousatov-ua/memgraph-ingester#cli-reference).
