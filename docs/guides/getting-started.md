# Getting Started

## What Memgraph Ingester Does

Memgraph Ingester indexes source files into [Memgraph](https://memgraph.com/) so agents can work
with a real code graph instead of a loose pile of text chunks.

It captures structure such as files, packages, types, methods, fields, inheritance, implementations,
annotations, decorators, and best-effort call relationships. It also creates optional RAG chunks
that point back to source graph records, so semantic discovery can be verified through exact graph
lookups.

## Why This Saves Agent Context

Memgraph Ingester helps agents start from structure instead of raw file scanning. They can ask the
graph for the likely files, symbols, callers, callees, and semantic anchors before spending context
on source reads.

In a six-task comparison, Memgraph-backed runs used **41% fewer tokens overall**, with the strongest
real task saving **62%**. They also needed **45% fewer tool calls** and **44% fewer opened files**.

| Metric | With Memgraph | Without Memgraph | Reduction |
| --- | ---: | ---: | ---: |
| Tokens used | 354,970 | 605,578 | **41% fewer** |
| Tool calls | 92 | 168 | **45% fewer** |
| Files opened | 59 | 105 | **44% fewer** |

These measurements were performed on this codebase. At the time of measurement, `cloc` reported
**28,588 code lines** across tracked Java, JavaScript, Python, and TypeScript sources: **26,006**
Java, **1,863** JavaScript, **645** Python, and **74** TypeScript.

Measured task scope:

| Task | Description |
| --- | --- |
| A | Provider onboarding |
| B | Write-path performance |
| C | Refactor impact |
| D | Stale-snippet triage |
| E | Stale-snippet fix |
| F | CI triage |

Quality remained high across the measured tasks, while the graph-guided runs spent less effort on
discovery and kept more context available for understanding, editing, and verification. In the
measured set, Memgraph also avoided the false positive seen without the graph.

Please visit the latest benchmark results in [benchmarks](https://github.com/ousatov-ua/memgraph-ingester/tree/main/benchmarks)

Supported inputs:

| Language path | Coverage |
| --- | --- |
| Java | First-class parser with JavaParser and optional classpath-based symbol resolution |
| JavaScript and TypeScript | First-class parser with managed or system Node.js runtime |
| Python | First-class parser with managed or system CPython runtime |
| Other ctags-detected languages | Structural file, owner, type, method, and field inventory |

## Requirements

For normal use you need:

- A Memgraph instance with Bolt enabled.
- A Memgraph Ingester executable or shaded JAR.
- `memgraph-ingester-mcp` when you want agents to use high-level graph tools.

Native binaries do not require Java, Node.js, Python, or ctags for normal managed-mode parsing.
The shaded JAR requires a Java 25 JRE.

## 1. Start Memgraph

```bash
docker run -p 7687:7687 -p 7444:7444 --name memgraph memgraph/memgraph-mage:3.9.0
```

Memgraph Bolt will listen on `bolt://localhost:7687`.

## 2. Install Memgraph Ingester

With Homebrew:

```bash
brew tap ousatov-ua/memgraph-ingester
brew install memgraph-ingester
```

Or download a release asset from
[GitHub Releases](https://github.com/ousatov-ua/memgraph-ingester/releases).

For the shaded JAR:

```bash
java -jar /path/to/memgraph-ingester.jar --help
```

In the examples below, replace `memgraph-ingester` with either the native executable or
`java -jar /path/to/memgraph-ingester.jar`.

## 3. Ingest a Project

Run this from the repository you want to index:

```bash
memgraph-ingester \
  --source . \
  --bolt bolt://localhost:7687 \
  --project my-project \
  --wipe-project-code \
  --init-instructions \
  --apply-schema
```

This command:

- Applies graph schema setup.
- Clears only code graph data for `my-project`.
- Scans files under the current directory.
- Writes graph nodes and relationships to Memgraph.
- Installs agent instructions for the project.

Add memories when you want durable project knowledge too:

```bash
memgraph-ingester \
  --source . \
  --bolt bolt://localhost:7687 \
  --project my-project \
  --with-memories \
  --wipe-project-code \
  --init-instructions \
  --apply-schema
```

## 4. Verify the Graph

With `memgraph-ingester-mcp`, ask your agent to call `server_status` for the project.

DIY. With `mgconsole`:

```bash
mgconsole --host localhost --port 7687 --output-format=csv
```

```cypher
MATCH (p:Project {name: 'my-project'})-[:CONTAINS]->(l:Language)-[:CONTAINS]->(c:Code)
RETURN p.name, l.name, c.sourceRoots, c.lastIngested
ORDER BY c.lastIngested DESC;
```

## 5. Re-ingest While You Work

Refresh the graph after code changes:

```bash
memgraph-ingester \
  --source . \
  --bolt bolt://localhost:7687 \
  --project my-project
```

To keep the graph fresh continuously:

```bash
memgraph-ingester \
  --source . \
  --bolt bolt://localhost:7687 \
  --project my-project \
  --watch
```

## Safety Notes

Project data is scoped by `--project`. `--wipe-project-code` deletes only the named project's code
graph. Memory is protected unless you explicitly pass `--wipe-project-memories`.

`--wipe-all` deletes all Memgraph data. Use it only when you intentionally want an empty database.
