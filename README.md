# Memgraph Ingester. Speed up your AI agent!

[![Build](https://github.com/ousatov-ua/memgraph-ingester/actions/workflows/maven.yml/badge.svg)](https://github.com/ousatov-ua/memgraph-ingester/actions/workflows/maven.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.ousatov-ua/memgraph-ingester)](https://central.sonatype.com/artifact/io.github.ousatov-ua/memgraph-ingester)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Visitors](https://visitor-badge.laobi.icu/badge?page_id=ousatov-ua.memgraph-ingester)](https://github.com/ousatov-ua/memgraph-ingester)
[![GitHub commits](https://img.shields.io/github/commit-activity/t/ousatov-ua/memgraph-ingester)](https://github.com/ousatov-ua/memgraph-ingester/commits/main)
[![GitHub last commit](https://img.shields.io/github/last-commit/ousatov-ua/memgraph-ingester)](https://github.com/ousatov-ua/memgraph-ingester/commits/main)

Ingests the structural model of a Java codebase into [Memgraph](https://memgraph.com/) as a
queryable **code + memory knowledge graph**, combining source structure with persistent engineering
context (decisions, rules, findings, etc.).

Paired with the
[Memgraph MCP server](https://github.com/memgraph/ai-toolkit/tree/main/integrations/mcp-memgraph),
this enables Claude Code (or any MCP-aware client) to reason over both code and accumulated project
knowledge via graph queries instead of raw text search — improving accuracy, reducing cost, and
speeding up analysis.

You can use the code in this repo as-is, or fork it and customize it to your needs.
[Memgraph](https://memgraph.com/) is free too.
Please submit any issues or pull requests.

## What it does

Memgraph Ingester creates two project-scoped graphs for a Java codebase:

- A **Code graph** under `(:Project)-[:CONTAINS]->(:Code)`
- A **Memory graph** under `(:Project)-[:HAS_MEMORY]->(:Memory)`

Every code and memory node is scoped by a `project` property, so multiple Java codebases can share
the same Memgraph instance without collisions.

The **Code graph** stores Java source structure in a queryable, persistent form. The ingester walks
the source tree with [JavaParser](https://github.com/javaparser/javaparser) and symbol resolution,
then writes packages, files, classes, interfaces, annotations, methods, fields, inheritance,
and within-project call relationships.

The parser is configured for Java 25 syntax. It should handle most sources written for earlier Java
versions too, but JavaParser is not a `javac` replacement and may still miss unsupported or
edge-case constructs.

The **Memory graph** stores durable engineering context: decisions, ADRs, rules, findings, tasks,
risks, questions, ideas, and domain notes. Memory items can refer to stable `:CodeRef` nodes, which
are resolved back to the current code graph after ingestion. This lets agents query both
**structure (code)** and **knowledge (memory)** without relying only on raw text search.

See [`SCHEMA.md`](schema/SCHEMA.md) for the full graph model.

## Requirements

- Required: **Java 25 JRE** to run
- Required: Memgraph instance (or Docker)
- Optional: **Java 25 SDK**, **Maven 3.9+** to build
- Optional: `mgconsole`

## Quick start

- Download the latest jar (v5.0.21 the latest for now)
```bash
wget https://github.com/ousatov-ua/memgraph-ingester/releases/download/v5.0.21/memgraph-ingester.jar
```
- Run Memgraph
```bash
docker run -p 7687:7687 -p 7444:7444 --name memgraph memgraph/memgraph-mage:3.9.0
```
- Ingest the project
```bash
cd $your_project
java -jar path/to/memgraph-ingester.jar \
  --source /path/to/your/java/project/src/main/java \
  --bolt bolt://localhost:7687 \
  --project my-project \
  --wipe-all \
  --apply-schema
```
- Append knowledge for your agent
```bash
# GitHub Copilot
curl -s https://raw.githubusercontent.com/ousatov-ua/memgraph-ingester/refs/heads/main/script/init-memgraph-github.sh \
  | bash -s -- my-project
# Claude
curl -s https://raw.githubusercontent.com/ousatov-ua/memgraph-ingester/refs/heads/main/script/init-memgraph-claude.sh \
  | bash -s -- my-project
# Codex
curl -s https://raw.githubusercontent.com/ousatov-ua/memgraph-ingester/refs/heads/main/script/init-memgraph-codex.sh \
  | bash -s -- my-project
# Gemini
curl -s https://raw.githubusercontent.com/ousatov-ua/memgraph-ingester/refs/heads/main/script/init-memgraph-gemini.sh \
  | bash -s -- my-project
```

## Going further

### Maven dependency (optional)

```xml

<dependency>
  <groupId>io.github.ousatov-ua</groupId>
  <artifactId>memgraph-ingester</artifactId>
  <version><!-- see latest on Maven Central --></version>
</dependency>
```

## Quick start

### 1. Start Memgraph

- With UI:
```bash
cd memgraph-platform
docker-compose up -d
```
- No UI

```bash
docker run -p 7687:7687 -p 7444:7444 --name memgraph memgraph/memgraph-mage:3.9.0
```

Bolt listens on `localhost:7687`.

### 2. Build the ingester

```bash
git clone https://github.com/ousatov-ua/memgraph-ingester.git
cd memgraph-ingester
mvn clean package -Pshade -DskipTests
```

Produces a shaded fat JAR at `target/memgraph-ingester.jar`.

Or use published shaded fat JAR in [releases](https://github.com/ousatov-ua/memgraph-ingester/releases) page.

### 3. Apply the schema (one-time per Memgraph instance)

```bash
cat src/main/resources/io/github/ousatov/tools/memgraph/cypher/create-schema.cypher | mgconsole --host localhost --port 7687
```

Creates uniqueness constraints and lookup indexes for both the code graph and the memory graph. Safe
to re-run — existing constraints are reported and skipped.

You can also use the CLI. This command will apply the schema to the `memgraph` database first, then
ingest the project:

```bash
java -jar target/memgraph-ingester.jar \
  --source /path/to/your/java/project/src/main/java \
  --bolt bolt://localhost:7687 \
  --project my-project \
  --apply-schema
```

Next command will also wipe **all** data in the `memgraph` database first, then will apply the
schema and ingest the project:

```bash
java -jar target/memgraph-ingester.jar \
  --source /path/to/your/java/project/src/main/java \
  --bolt bolt://localhost:7687 \
  --project my-project \
  --wipe-all \
  --apply-schema
```

### 4. Ingest a project

This will wipe the Code graph for this project first:

```bash
java -jar target/memgraph-ingester.jar \
  --source /path/to/your/java/project/src/main/java \
  --bolt bolt://localhost:7687 \
  --project my-project \
  --wipe-project-code
```

This will wipe the Code and Memory graph for this project first:

```bash
java -jar target/memgraph-ingester.jar \
  --source /path/to/your/java/project/src/main/java \
  --bolt bolt://localhost:7687 \
  --project my-project \
  --wipe-project-code \
  --wipe-project-memories
```

### 5. Verify

```cypher

MATCH (p:Project)-[:CONTAINS]->(c:Code)
RETURN p.name, c.sourceRoots, c.lastIngested;
```

You should see your project with a fresh `lastIngested` timestamp.

## CLI options

| Option                    | Short | Required | Default | Description                                                          |
|---------------------------|-------|----------|---------|----------------------------------------------------------------------|
| `--source`                | `-s`  | yes      |         | Root directory to scan (e.g. `src/main/java`)                        |
| `--bolt`                  | `-b`  | yes      |         | Bolt URL, e.g. `bolt://localhost:7687`                               |
| `--project`               | `-P`  | yes      |         | Logical project name. Namespaces all nodes.                          |
| `--user`                  | `-u`  | no       |         | Memgraph username (empty by default)                                 |
| `--pass`                  | `-p`  | no       |         | Memgraph password (empty by default)                                 |
| `--threads`               | `-t`  | no       | 1       | Parser threads (default `1`). Each thread gets its own Bolt session. |
| `--wipe-project-code`     | no    | no       | false   | Delete this project's code graph before ingesting                    |
| `--wipe-project-memories` | no    | no       | false   | Delete this project's memory graph before ingesting                  |
| `--apply-schema`          | no    | no       | false   | Apply schema before ingesting                                        |
| `--wipe-all`              | no    | no       | false   | Wipe all data (schema will be dropped first)                         |

`--wipe-project-code` only affects code nodes matching the given `--project`; other codebases in the
same Memgraph instance are untouched, and the `:Project` anchor remains.
`--wipe-project-memories` only affects memory nodes matching the given `--project`; the code graph
and
the `:Project` anchor remain.

### Parallel ingestion

Large codebases ingest faster with multiple parser threads:

```bash
java -jar target/memgraph-ingester.jar \
  --source /path/to/your/java/project/src/main/java \
  --bolt bolt://localhost:7687 \
  --project my-project \
  --wipe-project-code \
  --threads 8
```

Each thread holds its own `JavaParser` and its own Bolt session. The `Driver` itself is shared.

**Realistic speedup** — don't expect linear scaling. JavaParser work is CPU-bound and parallelizes
well, but Memgraph Community serializes writes internally, so the write path bottlenecks quickly:

| Threads | Typical speedup | Bottleneck                 |
|---------|-----------------|----------------------------|
| 1       | 1× (baseline)   | Sequential parse + write   |
| 4       | ~2.5–3×         | Write serialization starts |
| 8       | ~3–4×           | Diminishing returns        |
| 16+     | ~3–4×           | Writes fully saturated     |

4–8 threads is the sweet spot on most machines. Values higher than your CPU core count rarely help.

**Determinism note**: with `--threads > 1`, file processing order is non-deterministic. MERGE is
idempotent, so results are identical, but log order will vary between runs.

## Using with AI agents

This repo ships scripts designed to
be dropped into any project that's been
ingested. It tells AI agents how to scope queries to the right project, how the schema is shaped,
when to reach for the graph vs. filesystem search, and how to use Memories for durable decisions
and follow-up context.

### Per-repo setup

#### CLAUDE

Use the bundled [`init-memgraph-claude.sh`](script/init-memgraph-claude.sh) script, which fetches
the template, substitutes the
project name, and appends the result to the local `CLAUDE.md`

Run it from inside the repo you just ingested:

```bash
# Point at the script in your local checkout
/path/to/memgraph-ingester/script/init-memgraph-claude.sh my-project
```

Or fetch-and-run straight from GitHub:

```bash
curl -s https://raw.githubusercontent.com/ousatov-ua/memgraph-ingester/refs/heads/main/script/init-memgraph-claude.sh \
  | bash -s -- my-project
```

Commit the updated `CLAUDE.md`. Claude Code reads it on every session start.

#### CODEX

Use the bundled [`init-memgraph-codex.sh`](script/init-memgraph-codex.sh) script, which fetches
the template, substitutes the
project name, and appends the result to the local `AGENTS.md`

Run it from inside the repo you just ingested:

```bash
# Point at the script in your local checkout
/path/to/memgraph-ingester/script/init-memgraph-codex.sh my-project
```

Or fetch-and-run straight from GitHub:

```bash
curl -s https://raw.githubusercontent.com/ousatov-ua/memgraph-ingester/refs/heads/main/script/init-memgraph-codex.sh \
  | bash -s -- my-project
```

Commit the updated `AGENTS.md`. Codex reads it on every session start.

#### GEMINI

Use the bundled [`init-memgraph-gemini.sh`](script/init-memgraph-gemini.sh) script, which fetches
the template, substitutes the
project name, and appends the result to the local `AGENTS.md`

Run it from inside the repo you just ingested:

```bash
# Point at the script in your local checkout
/path/to/memgraph-ingester/script/init-memgraph-gemini.sh my-project
```

Or fetch-and-run straight from GitHub:

```bash
curl -s https://raw.githubusercontent.com/ousatov-ua/memgraph-ingester/refs/heads/main/script/init-memgraph-gemini.sh \
  | bash -s -- my-project
```

Commit the updated `AGENTS.md`. Gemini reads it on every session start.

#### GITHUB COPILOT

Use the bundled [`init-memgraph-github.sh`](script/init-memgraph-github.sh) script, which fetches
the template, substitutes the
project name, and appends the result to the local `AGENTS.md`

Run it from inside the repo you just ingested:

```bash
# Point at the script in your local checkout
/path/to/memgraph-ingester/script/init-memgraph-github.sh my-project
```

Or fetch-and-run straight from GitHub:

```bash
curl -s https://raw.githubusercontent.com/ousatov-ua/memgraph-ingester/refs/heads/main/script/init-memgraph-github.sh \
  | bash -s -- my-project
```

Commit the updated `AGENTS.md`. GitHub Copilot reads it on every session start.

### MCP server setup

#### CLAUDE

Claude Code needs the Memgraph MCP server to actually run queries. Minimal project-scoped config in
`.mcp.json`:

```json
{
  "mcpServers": {
    "memgraph": {
      "command": "uvx",
      "args": [
        "mcp-memgraph"
      ],
      "env": {
        "MEMGRAPH_URL": "bolt://localhost:7687",
        "MCP_READ_ONLY": "true"
      }
    }
  }
}
```

Please set `MCP_READ_ONLY` to `"false"` if you want to have Memories captured

Verify it's registered:

```bash
claude mcp list
```

#### CODEX

Codex needs the Memgraph MCP server to actually run queries. Minimal project-scoped config in
`~/.codex/config.toml`:

```toml
[mcp_servers.memgraph]

command = "uv"
args = [
    "run",
    "--with",
    "mcp-memgraph",
    "--python",
    "3.13",
    "mcp-memgraph"
]
[mcp_servers.memgraph.env]
MCP_TRANSPORT = "stdio"
MEMGRAPH_URL = "bolt://localhost:7687"
MEMGRAPH_USER = "memgraph"
MEMGRAPH_PASSWORD = ""
MEMGRAPH_DATABASE = "memgraph"
MCP_READ_ONLY = "false"

[mcp_servers.memgraph.tools.run_query]
approval_mode = "approve"
```

The Codex example is read-only. To let an agent create or update Memory nodes, use a writable MCP
connection, for example, by setting `MCP_READ_ONLY = "false"` and keeping `run_query` approval
enabled.

Verify it's registered:

```bash
codex mcp list
```

#### GEMINI

Codex needs the Memgraph MCP server to actually run queries. Minimal project-scoped config in
`~/.gemini/settings.json`:

```json
{
  "mcpServers": {
    "mcp-memgraph": {
      "command": "uvx",
      "args": [
        "mcp-memgraph"
      ],
      "env": {
        "MEMGRAPH_URL": "bolt://localhost:7687",
        "MCP_READ_ONLY": "false"
      },
      "timeout": 5000,
      "trust": true
    }
  }
}
```

The example is read-only. To let an agent create or update Memory nodes, use a writable MCP
connection, for example, by setting `MCP_READ_ONLY = "false"` and keeping `run_query` approval
enabled.

Verify it's registered:

```bash
gemini mcp list
```

#### GITHUB COPILOT

GitHub Copilot needs the Memgraph MCP server to actually run queries. Minimal project-scoped config
in
`~/.copilot/mcp-config.json`:

```json
{
  "mcpServers": {
    "mcp-memgraph": {
      "type": "local",
      "command": "uvx",
      "args": [
        "mcp-memgraph"
      ],
      "env": {
        "MEMGRAPH_URL": "bolt://localhost:7687",
        "MCP_READ_ONLY": "false"
      },
      "tools": [
        "*"
      ]
    }
  }
}
```

## Re-ingesting after code changes

The graph goes stale as code changes. Re-run the ingester with `--wipe-project-code` to refresh:

```bash
java -jar target/memgraph-ingester.jar \
  --source /path/to/your/java/project/src/main/java \
  --bolt bolt://localhost:7687 \
  --project my-project \
  --wipe-project-code
```

Check freshness anytime:

```cypher

MATCH (:Project {name: 'my-project'})-[:CONTAINS]->(c:Code)
RETURN c.sourceRoots, c.lastIngested;
```

## Multiple projects in one Memgraph instance

Run the ingester once per codebase with different `--project` values. Each gets its own
`:Project -> :Code` anchor chain; code nodes are composite-keyed by `(key, project)`, so nothing
collides.

```bash
java -jar target/memgraph-ingester.jar -s ~/code/repo-a/src/main/java -b bolt://localhost:7687 -P repo-a --wipe-project-code
java -jar target/memgraph-ingester.jar -s ~/code/repo-b/src/main/java -b bolt://localhost:7687 -P repo-b --wipe-project-code
```

List everything that's indexed:

```cypher

MATCH (p:Project)-[:CONTAINS]->(c:Code)
RETURN p.name, c.sourceRoots, c.lastIngested
  ORDER BY c.lastIngested DESC;
```

## What gets captured

| Node label    | Identity               |
|---------------|------------------------|
| `:Project`    | `name`                 |
| `:Code`       | `project`              |
| `:Package`    | `(name, project)`      |
| `:File`       | `(path, project)`      |
| `:Class`      | `(fqn, project)`       |
| `:Interface`  | `(fqn, project)`       |
| `:Annotation` | `(fqn, project)`       |
| `:Method`     | `(signature, project)` |
| `:Field`      | `(fqn, project)`       |

| Relationship                                                    | Meaning                   |
|-----------------------------------------------------------------|---------------------------|
| `(:Project)-[:CONTAINS]->(:Code)`                               | Code graph anchor         |
| `(:Code)-[:CONTAINS]->(:Package \| :File)`                      | Top-level code membership |
| `(:Package)-[:CONTAINS]->(:Class \| :Interface \| :Annotation)` | Package contents          |
| `(:File)-[:DEFINES]->(:Class \| :Interface \| :Annotation)`     | Source location           |
| `(:Class)-[:EXTENDS]->(:Class)`                                 | Class inheritance         |
| `(:Class)-[:IMPLEMENTS]->(:Interface)`                          | Interface implementation  |
| `(:Interface)-[:EXTENDS]->(:Interface)`                         | Interface inheritance     |
| `(:Class \| :Interface)-[:DECLARES]->(:Method \| :Field)`       | Type members              |
| `(:Method)-[:CALLS]->(:Method)`                                 | Call graph (best-effort)  |
| `(:*)-[:ANNOTATED_WITH]->(:Annotation)`                         | Annotation usage          |

Memory nodes are manually authored by agents or clients and share the same project namespace:

| Node label  | Identity                     | Typical use                                      |
|-------------|------------------------------|--------------------------------------------------|
| `:Memory`   | `project`                    | Root for project memory                          |
| `:Decision` | `(id, project)`              | Accepted or rejected decisions                   |
| `:ADR`      | `(id, project)`              | Architecture decision records                    |
| `:Rule`     | `(id, project)`              | Hard or soft project constraints                 |
| `:Context`  | `(id, project)`              | Durable explanatory context                      |
| `:Finding`  | `(id, project)`              | Bugs, risks, performance findings                |
| `:Task`     | `(id, project)`              | Follow-up work                                   |
| `:Risk`     | `(id, project)`              | Known risks and mitigations                      |
| `:Question` | `(id, project)`              | Open or answered questions                       |
| `:Idea`     | `(id, project)`              | Proposed ideas and alternatives                  |
| `:CodeRef`  | `(project, targetType, key)` | Stable reference to code that may be re-ingested |

| Relationship                                                                                                          | Meaning                                    |
|-----------------------------------------------------------------------------------------------------------------------|--------------------------------------------|
| `(:Project)-[:HAS_MEMORY]->(:Memory)`                                                                                 | Memory graph anchor                        |
| `(:Memory)-[:HAS_DECISION \| :HAS_ADR \| :HAS_RULE \| :HAS_CONTEXT]->(:*)`                                            | Memory item ownership                      |
| `(:Memory)-[:HAS_FINDING \| :HAS_TASK \| :HAS_RISK \| :HAS_QUESTION]->(:*)`                                           | Memory item ownership                      |
| `(:Memory)-[:HAS_IDEA]->(:Idea)`                                                                                      | Memory item ownership                      |
| `(:Decision \| :ADR \| :Rule \| :Context \| :Finding \| :Task \| :Risk \| :Idea)-[:REFERS_TO]->(:CodeRef)`            | Memory-to-code reference                   |
| `(:CodeRef)-[:RESOLVES_TO]->(:Code \| :Package \| :File \| :Class \| :Interface \| :Annotation \| :Method \| :Field)` | Current code node resolved after ingestion |

`CodeRef.targetType` is one of `Code`, `Package`, `File`, `Class`, `Interface`, `Annotation`,
`Method`, or `Field`. `CodeRef.key` uses the matching code identity: project name for `Code`,
package name for `Package`, path for `File`, FQN for types/annotations/fields, and signature for
`Method`. The ingester refreshes `RESOLVES_TO` edges after each run, so memory can survive code
graph wipes and re-ingestion.

## Caveats

- **`CALLS` is best-effort.** JavaParser can't always resolve callees (external libraries without
  classpath, complex generics, lambdas). Transitive call queries may miss edges — a missing edge
  does not prove the call doesn't happen.
- **External types get tagged with your project.** When a class extends or implements something from
  outside your source tree (e.g. `RuntimeException`, Spring interfaces), the ingester creates a
  `:Class` or `:Interface` node for it and scopes it to your project. This keeps inheritance edges
  intact but means those nodes are placeholders without full metadata.
- **Generated code is only indexed if you ingest it.** Annotation processors, Lombok-generated
  members, and similar won't appear in the graph unless their generated source directory is passed
  to the ingester too:

```shell
java -jar memgraph-ingester.jar \
  --source target/generated-sources/annotations \
  --bolt bolt://localhost:7687 \
  --project work
  # no --wipe-project-code here!!!!
```

## Project layout

```
.
├── src/main/java/                          # Ingester source (IngesterCli + support)
├── src/main/resources/io/github/ousatov/tools/memgraph/cypher/schema # Memgraph cypher
├── scripts/
│   └── init-memgraph-claude.sh             # Helper: appends Memgraph section to a repo's CLAUDE.md
├── schema/
│   └── SCHEMA.md                           # Graph model reference (human-readable)
├── template/
│   └── CLAUDE-memgraph-template.md         # Template appended to project CLAUDE.md files
├── pom.xml                                 # Maven build (shaded fat JAR, spotless-enforced)
└── README.md
```

## License

MIT — see [`LICENSE`](LICENSE).
