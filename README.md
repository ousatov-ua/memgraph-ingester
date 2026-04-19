# memgraph-ingester

Ingests the structural model of a Java codebase into [Memgraph](https://memgraph.com/) as a queryable code knowledge graph. Pair it with the [Memgraph MCP server](https://github.com/memgraph/ai-toolkit/tree/main/integrations/mcp-memgraph) to let Claude Code (or any MCP-aware client) reason about your code through graph queries instead of raw text search which can significantly reduce money spending and increase speed.

## What it does

Walks a Java source tree, parses each file with [JavaParser](https://github.com/javaparser/javaparser) (with symbol resolution), and writes a graph of packages, files, classes, interfaces, methods, fields, inheritance, and call relationships. Every node is scoped by a `project` property and anchored by a `:Project` node, so **multiple codebases can share one Memgraph instance** without collisions.

See [`SCHEMA.md`](schema/SCHEMA.md) for the full graph model.

## Requirements

- **Java 25** to build and run
- **Maven 3.9+**
- A running **Memgraph** instance (local Docker works fine)
- Optional: `mgconsole` for applying the schema

## Quick start

### 1. Start Memgraph

```bash
docker run -p 7687:7687 -p 7444:7444 --name memgraph memgraph/memgraph-platform
```

Bolt listens on `localhost:7687`.

### 2. Build the ingester

```bash
git clone https://github.com/ousatov-ua/memgraph-ingester.git
cd memgraph-ingester
mvn clean package
```

Produces a shaded fat JAR at `target/memgraph-ingester-1.0.0.jar`.

### 3. Apply the schema (one-time per Memgraph instance)

```bash
cat schema.cypher | mgconsole --host localhost --port 7687
```

Creates uniqueness constraints and lookup indexes. Safe to re-run — existing constraints are reported and skipped.

### 4. Ingest a project

```bash
java -jar target/memgraph-ingester-1.0.0.jar \
  --source /path/to/your/java/project/src/main/java \
  --bolt bolt://localhost:7687 \
  --project my-project \
  --wipe
```

### 5. Verify

```cypher
MATCH (p:Project) RETURN p.name, p.sourceRoot, p.lastIngested;
```

You should see your project with a fresh `lastIngested` timestamp.

## CLI options

| Option | Short | Required | Description |
|---|---|---|---|
| `--source` | `-s` | yes | Root directory to scan (e.g. `src/main/java`) |
| `--bolt` | `-b` | yes | Bolt URL, e.g. `bolt://localhost:7687` |
| `--project` | `-P` | yes | Logical project name. Namespaces all nodes. |
| `--user` | `-u` | no | Memgraph username (empty by default) |
| `--pass` | `-p` | no | Memgraph password (empty by default) |
| `--wipe` | | no | Delete everything under this project before ingesting |

`--wipe` only affects nodes matching the given `--project`; other codebases in the same Memgraph instance are untouched.

## Using with Claude Code

This repo ships a `CLAUDE-memgraph-template.md` designed to be dropped into any project that's been ingested. It tells Claude Code how to scope queries to the right project, how the schema is shaped, and when to reach for the graph vs. filesystem search.

### Per-repo setup

Use the bundled `init-memgraph-claude.sh` script, which fetches the template, substitutes the project name, and appends the result to the local `CLAUDE.md`. The script lives at `script/init-memgraph-claude.sh` in this repo.

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

### MCP server setup

Claude Code needs the Memgraph MCP server to actually run queries. Minimal project-scoped config in `.mcp.json`:

```json
{
  "mcpServers": {
    "memgraph": {
      "command": "uvx",
      "args": ["mcp-memgraph"],
      "env": {
        "MEMGRAPH_URL": "bolt://localhost:7687"
      }
    }
  }
}
```

Verify it's registered:

```bash
claude mcp list
```

## Re-ingesting after code changes

The graph goes stale as code changes. Re-run the ingester with `--wipe` to refresh:

```bash
java -jar target/memgraph-ingester-1.0.0.jar \
  --source /path/to/your/java/project/src/main/java \
  --bolt bolt://localhost:7687 \
  --project my-project \
  --wipe
```

Check freshness anytime:

```cypher
MATCH (p:Project {name: 'my-project'}) RETURN p.lastIngested;
```

## Multiple projects in one Memgraph instance

Run the ingester once per codebase with different `--project` values. Each gets its own `:Project` anchor; nodes are composite-keyed by `(key, project)`, so nothing collides.

```bash
java -jar target/memgraph-ingester-1.0.0.jar -s ~/code/repo-a/src/main/java -b bolt://localhost:7687 -P repo-a --wipe
java -jar target/memgraph-ingester-1.0.0.jar -s ~/code/repo-b/src/main/java -b bolt://localhost:7687 -P repo-b --wipe
```

List everything that's indexed:

```cypher
MATCH (p:Project) RETURN p.name, p.sourceRoot, p.lastIngested
ORDER BY p.lastIngested DESC;
```

## What gets captured

| Node label | Identity |
|---|---|
| `:Project` | `name` |
| `:Package` | `(name, project)` |
| `:File` | `(path, project)` |
| `:Class` | `(fqn, project)` |
| `:Interface` | `(fqn, project)` |
| `:Method` | `(signature, project)` |
| `:Field` | `(fqn, project)` |

| Relationship | Meaning |
|---|---|
| `(:Project)-[:CONTAINS]->(:Package \| :File)` | Top-level membership |
| `(:Package)-[:CONTAINS]->(:Class \| :Interface)` | Package contents |
| `(:File)-[:DEFINES]->(:Class \| :Interface)` | Source location |
| `(:Class)-[:EXTENDS]->(:Class)` | Class inheritance |
| `(:Class)-[:IMPLEMENTS]->(:Interface)` | Interface implementation |
| `(:Interface)-[:EXTENDS]->(:Interface)` | Interface inheritance |
| `(:Class \| :Interface)-[:DECLARES]->(:Method \| :Field)` | Type members |
| `(:Method)-[:CALLS]->(:Method)` | Call graph (best-effort) |

## Caveats

- **`CALLS` is best-effort.** JavaParser can't always resolve callees (external libraries without classpath, complex generics, lambdas). Transitive call queries may miss edges — a missing edge does not prove the call doesn't happen.
- **External types get tagged with your project.** When a class extends or implements something from outside your source tree (e.g. `RuntimeException`, Spring interfaces), the ingester creates a `:Class` or `:Interface` node for it and scopes it to your project. This keeps inheritance edges intact but means those nodes are placeholders without full metadata.
- **Annotations and generated code are not indexed** in v1.0.0. Annotation processors, Lombok-generated members, and similar won't appear in the graph. To index them too, you just need to run the ingester again:

```shell
java -jar memgraph-ingester.jar \
  --source target/generated-sources/annotations \
  --bolt bolt://localhost:7687 \
  --project work
  # not --wipe here!!!!
```

## License

MIT — see [`LICENSE`](LICENSE).
