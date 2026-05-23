# Memgraph Ingester

[![Build](https://github.com/ousatov-ua/memgraph-ingester/actions/workflows/maven.yml/badge.svg)](https://github.com/ousatov-ua/memgraph-ingester/actions/workflows/maven.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.ousatov-ua/memgraph-ingester)](https://central.sonatype.com/artifact/io.github.ousatov-ua/memgraph-ingester)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Visitors](https://visitor-badge.laobi.icu/badge?page_id=ousatov-ua.memgraph-ingester)](https://github.com/ousatov-ua/memgraph-ingester)
[![GitHub commits](https://img.shields.io/github/commit-activity/t/ousatov-ua/memgraph-ingester)](https://github.com/ousatov-ua/memgraph-ingester/commits/main)
[![GitHub last commit](https://img.shields.io/github/last-commit/ousatov-ua/memgraph-ingester)](https://github.com/ousatov-ua/memgraph-ingester/commits/main)
[![Supports Java codebases](https://img.shields.io/badge/supports-Java-f89820?logo=openjdk&logoColor=white)](#3-ingest-java)
[![Supports JavaScript/TypeScript codebases](https://img.shields.io/badge/supports-JavaScript%20%2F%20TypeScript-3178c6?logo=typescript&logoColor=white)](#4-ingest-javascript-or-typescript)

![memgraph-ingester-readme-banner-640x320.svg](image/memgraph-ingester-readme-banner-640x320.svg)

Memgraph Ingester indexes **Java** and **JavaScript/TypeScript** source files into
[Memgraph](https://memgraph.com/) so AI agents can query a real code graph instead of repeatedly
scanning raw files.

Use it when you want your agent to quickly answer questions such as:

- What classes, methods, files, and packages exist?
- What extends or implements this type?
- Who calls this method?
- What durable project rules, decisions, findings, risks, and tasks should the agent remember?

The normal path is simple:

1. Run Memgraph.
2. Download one ingester executable.
3. Run one command; the ingester selects Java or JS/TS logic from each source file extension.
4. Optionally connect your AI agent through MCP or `mgconsole`.

No source code is uploaded by the ingester. It reads local files, writes graph nodes to your
Memgraph instance over Bolt, and exits.

## Safety First

The ingester is designed to be safe to try.

| Area | What happens |
|---|---|
| Project isolation | Every code and memory node is scoped by `--project`. Multiple repos can share one Memgraph instance. |
| Normal re-ingestion | `--wipe-project-code` deletes only the code graph for that `--project`. Other projects stay untouched. |
| Memory protection | Memory is not deleted unless you explicitly pass `--wipe-project-memories`. |
| Full database wipe | `--wipe-all` deletes all Memgraph data. Use it only when you intentionally want an empty database. |
| Schema setup | `--apply-schema` is safe to re-run. Existing constraints and indexes are skipped by Memgraph. |
| Java parsing | Java source is parsed locally with JavaParser. Dependencies are optional and only improve symbol resolution. |
| JS/TS parsing | The native executable can manage its own Node.js and TypeScript parser locally. You do not have to install Node.js. |

For JS/TS, managed runtime mode is explicit and controlled:

- Default mode is `--js-runtime-mode managed`.
- Managed mode downloads pinned Node.js `22.11.0` from `nodejs.org`.
- It verifies Node.js with the official SHA-256 checksum before extracting.
- It downloads pinned TypeScript `5.6.3` from the npm registry.
- It verifies the TypeScript package with SHA-512 integrity metadata.
- It caches both under `~/.cache/memgraph-ingester` by default.
- It never installs Node.js globally.
- It never runs `npm install` in your project.
- It skips `node_modules` during source ingestion and watch registration.

If you do not want the ingester to download Node.js, use your own Node.js:

```bash
<ingester> \
  --source . \
  --bolt bolt://localhost:7687 \
  --project my-js-project \
  --js-runtime-mode system \
  --wipe-project-code \
  --apply-schema
```

If you want no network access during JS/TS ingestion, warm the cache once and then use offline mode:

```bash
<ingester> --check-js-runtime

<ingester> \
  --source . \
  --bolt bolt://localhost:7687 \
  --project my-js-project \
  --js-runtime-mode offline \
  --wipe-project-code
```

## Requirements

For normal use:

- Memgraph, either local, Docker, or an existing Bolt endpoint.
- One ingester download from the release page.

Runtime requirements by artifact:

| Artifact | Java required? | Node.js required for JS/TS? |
|---|---:|---:|
| Native executable | No | No, managed mode handles it |
| Shaded JAR | Java 25 JRE | No, managed mode handles it |

Optional tools:

- `mgconsole`, if you want to query Memgraph directly without MCP (***produces much fewer tokens***)
- Maven, only if you want a richer Java classpath or you want to build from source.
- Java 25 SDK, only if you build from source.

## Quick Start

### 1. Start Memgraph

```bash
docker run -p 7687:7687 -p 7444:7444 --name memgraph memgraph/memgraph-mage:3.9.0
```

Memgraph Bolt listens on `bolt://localhost:7687`.

### 2. Download the Ingester

Version in this repository: `10.0.1`.

| Platform | Download                                                                                                                                             |
|---|------------------------------------------------------------------------------------------------------------------------------------------------------|
| Java shaded JAR | [memgraph-ingester.jar](https://github.com/ousatov-ua/memgraph-ingester/releases/download/v10.0.1/memgraph-ingester.jar)                             |
| Linux AMD64 | [memgraph-ingester-linux-amd64.zip](https://github.com/ousatov-ua/memgraph-ingester/releases/download/v10.0.1/memgraph-ingester-linux-amd64.zip)     |
| macOS ARM64 | [memgraph-ingester-macos-arm64.zip](https://github.com/ousatov-ua/memgraph-ingester/releases/download/v10.0.1/memgraph-ingester-macos-arm64.zip)     |
| Windows AMD64 | [memgraph-ingester-windows-amd64.zip](https://github.com/ousatov-ua/memgraph-ingester/releases/download/v10.0.1/memgraph-ingester-windows-amd64.zip) |

For native downloads:

```bash
unzip memgraph-ingester-macos-arm64.zip
chmod +x memgraph-ingester-macos-arm64
./memgraph-ingester-macos-arm64 --help
```

For the JAR:

```bash
java -jar /path/to/memgraph-ingester.jar --help
```

In commands below, replace `<ingester>` with either the native executable path or
`java -jar /path/to/memgraph-ingester.jar`.

### 3. Ingest Java

Simple Java ingestion:

```bash
cd /path/to/your/java/project

<ingester> \
  --source src/main/java \
  --bolt bolt://localhost:7687 \
  --project my-java-project \
  --init-instructions \
  --wipe-project-code \
  --apply-schema
```

Note: If you want to use memories too, then add `--with-memories`.

Better Java symbol resolution for Maven projects:

```bash
cd /path/to/your/java/project

CP=$(mvn -q dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=/dev/stdout 2>/dev/null)

<ingester> \
  --source src/main/java \
  --bolt bolt://localhost:7687 \
  --project my-java-project \
  --init-instructions \
  --classpath "$CP" \
  --wipe-project-code \
  --apply-schema
```

What the classpath improves:

- Fully qualified external types.
- More complete `EXTENDS` and `IMPLEMENTS` edges.
- More complete method signatures.
- More complete best-effort `CALLS` edges.

### 4. Ingest JavaScript or TypeScript

Managed mode needs no user-installed Node.js:

```bash
cd /path/to/your/js/project

<ingester> \
  --source . \
  --bolt bolt://localhost:7687 \
  --project my-js-project \
  --wipe-project-code \
  --init-instructions \
  --apply-schema
```

Optional preflight check, without connecting to Memgraph:

```bash
<ingester> --check-js-runtime
```

This downloads and verifies the managed parser runtime if needed, then runs a local parser smoke
test against temporary JS files.

### 5. Verify the Graph

With MCP, run the same Cypher through your agent. With `mgconsole`:

```bash
mgconsole --host localhost --port 7687 --output-format=csv
```

Then:

```cypher
MATCH (p:Project)-[:CONTAINS]->(l:Language)-[:CONTAINS]->(c:Code)
RETURN p.name, l.name, c.sourceRoots, c.lastIngested
ORDER BY c.lastIngested DESC;
```

You should see your project name and a fresh `lastIngested` timestamp.

## Common Commands

### Re-ingest after code changes

```bash
<ingester> \
  --source src/main/java \
  --bolt bolt://localhost:7687 \
  --project my-java-project \
  --wipe-project-code
```

### Faster re-runs

Use `--incremental` to skip files whose filesystem `lastModified` timestamp matches the graph:

```bash
<ingester> \
  --source src/main/java \
  --bolt bolt://localhost:7687 \
  --project my-java-project \
  --incremental
```

### Watch mode

Use `--watch` to keep the graph fresh while editing:

```bash
<ingester> \
  --source src/main/java \
  --bolt bolt://localhost:7687 \
  --project my-java-project \
  --watch
```

Watch mode recursively watches the source tree, debounces rapid saves, and re-ingests changed
files.

### Fresh project code and memory

This is safe for the named project, but it deletes that project's memory:

```bash
<ingester> \
  --source src/main/java \
  --bolt bolt://localhost:7687 \
  --project my-java-project \
  --wipe-project-code \
  --wipe-project-memories \
  --apply-schema
```

### Multiple projects in one Memgraph instance

```bash
<ingester> -s ~/code/repo-a/src/main/java -b bolt://localhost:7687 -P repo-a --wipe-project-code
<ingester> -s ~/code/repo-b/src/main/java -b bolt://localhost:7687 -P repo-b --wipe-project-code
```

List indexed projects:

```cypher
MATCH (p:Project)-[:CONTAINS]->(l:Language)-[:CONTAINS]->(c:Code)
RETURN p.name, l.name, c.sourceRoots, c.lastIngested
ORDER BY c.lastIngested DESC;
```

## Java Guide

The Java adapter reads `.java` files using JavaParser with Java 25 syntax support. It should handle
most earlier Java versions as well.

Captured Java structure:

- Packages and files.
- Classes, interfaces, annotations, enums, records, and nested classes.
- Methods, constructors, fields, visibility, return types, static flags, line ranges, and synthetic flags.
- `EXTENDS`, `IMPLEMENTS`, `DECLARES`, `DEFINES`, `CONTAINS`, `ANNOTATED_WITH`, and best-effort `CALLS`.

Use `--classpath` whenever you can. Without dependency JARs, the ingester still works, but external
types may fall back to simple names and some call edges may be missing.

For Maven projects:

```bash
CP=$(mvn -q dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=/dev/stdout 2>/dev/null)
```

Use `-DincludeScope=test` when you ingest tests or test fixtures. It includes JUnit, Testcontainers,
mocking libraries, and other test-scoped dependencies.

Generated code is indexed only if you ingest it:

```bash
<ingester> \
  --source target/generated-sources/annotations \
  --bolt bolt://localhost:7687 \
  --project my-java-project
```

Do not pass `--wipe-project-code` on the generated-source pass unless you want to replace the
previous graph with generated sources only.

## JavaScript and TypeScript Guide

JS/TS files are detected from their extensions during the normal `--source` scan.

Accepted source extensions:

- `.js`
- `.jsx`
- `.ts`
- `.tsx`
- `.mts`
- `.cts`
- `.d.ts`
- `.d.mts`
- `.d.cts`
- `.mjs`
- `.cjs`

Only source files under `--source` are considered. Use the repository root as `--source` when you
want root JavaScript config files or support scripts indexed alongside application source.
`tsconfig.json` and configs from its `extends` chain are read for TypeScript path aliases when
present, but they are not indexed as code nodes.

Skipped paths:

- Anything under `node_modules`.

Captured JS/TS structure:

- Files and synthetic module owners.
- Classes, named class expressions, and class expressions assigned to variables.
- Interfaces and type aliases as graph interfaces.
- Class/interface `EXTENDS` and class `IMPLEMENTS` relationships, including relative imports and
  `tsconfig.json` path aliases, including those inherited from extended configs, that resolve under
  `--source`.
- Interface and object-literal type members as `:Field`/`:Method` declarations.
- TypeScript enums as graph classes with `isEnum = true` and `kind = "enum"`, with enum members as
  `:Field` declarations using `kind = "enum-member"`.
- Top-level functions and variables under the module owner.
- Exported callable aliases and class re-export aliases as graph-visible declarations for their
  public export names.
- Methods, constructors, function-valued class fields, fields, abstract class metadata, bodyless
  abstract/optional method signatures, static flags, line ranges, and kinds.
- Decorators as annotations, preserving namespace-qualified decorator FQNs when possible.
- Angular decorators with framework metadata when detected.
- Syntax-based best-effort call edges, including top-level IIFEs/callbacks, local function
  constructors, typed `this.<property>` receivers for local classes, and deferred resolution for
  resolvable relative imports.
- Relative import and `tsconfig.json` path-alias resolution, including extended configs, prefers
  TypeScript source files over emitted JavaScript when both exist for the same local module path.

Runtime modes:

| Mode | Use when | Network |
|---|---|---|
| `managed` | You want the ingester to own the parser runtime. This is the default. | Downloads once if cache is missing. |
| `system` | You want to use `node` from `PATH`. | No Node download. TypeScript may still be managed unless already cached. |
| `offline` | You want no downloads and the cache is already warm. | No downloads. Fails if cache is missing. |

Custom cache:

```bash
<ingester> \
  --source . \
  --bolt bolt://localhost:7687 \
  --project my-js-project \
  --js-runtime-cache /path/to/cache \
  --wipe-project-code
```

Custom pinned versions:

```bash
<ingester> \
  --source . \
  --bolt bolt://localhost:7687 \
  --project my-js-project \
  --js-node-version 22.11.0 \
  --js-typescript-version 5.6.3 \
  --wipe-project-code
```

JS/TS caveat: JavaScript is dynamic. `CALLS` is not a complete raw AST call inventory; it records
known source call relationships when the helper can associate the site and target with graph
methods. Dynamic dispatch, dependency injection, monkey-patching, framework templates, and
generated code can produce missing call edges. A missing JS/TS `CALLS` edge does not prove a call
never happens.

## Agent Setup

The graph is useful directly, but it becomes much more powerful when your agent is told to use it.
The executable can write project-scoped Memgraph instructions to the agent's local instruction file.
It replaces its own previously managed block when one already exists, so rerunning the command keeps
`AGENTS.md`, `CLAUDE.md`, or another instruction file tidy.

Run the command from the repo you just ingested, using the same `--project` value.

Code graph guidance is installed by default:

```bash
memgraph-ingester --init-instructions -P my-project
memgraph-ingester -P my-project --instructions-agent codex
```

Add optional Memory workflow instructions when you want agents to create and maintain durable
Memgraph Memories:

```bash
memgraph-ingester --init-instructions -P my-project --with-memories
memgraph-ingester -P my-project --instructions-agent codex --with-memories
```

Agent presets choose the default target file:

```bash
memgraph-ingester --init-instructions -P my-project --instructions-agent codex
memgraph-ingester --init-instructions -P my-project --instructions-agent claude
memgraph-ingester --init-instructions -P my-project --instructions-agent gemini
memgraph-ingester --init-instructions -P my-project --instructions-agent github
```

When `--instructions-agent` is present, `--init-instructions` is optional.

Use `--instructions-file` to target a specific file:

```bash
memgraph-ingester -P my-project --instructions-file .github/copilot-instructions.md
```

Commit the updated instruction file so future agent sessions get the same graph guidance.

## MCP or mgconsole

MCP is optional. Agents can query Memgraph through MCP or via
`mgconsole`.

Use MCP when you want the agent to query the graph automatically.
Use `mgconsole` when you want direct, token-light Cypher output.

### Claude MCP

Minimal `.claude.json`:

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
        "MCP_READ_ONLY": "false"
      }
    }
  }
}
```

Verify:

```bash
claude mcp list
```

### Codex MCP

Minimal `~/.codex/config.toml`:

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

Verify:

```bash
codex mcp list
```

### Gemini MCP

Minimal `~/.gemini/settings.json`:

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

Verify:

```bash
gemini mcp list
```

### GitHub Copilot MCP

Minimal `~/.copilot/mcp-config.json`:

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

Set `MCP_READ_ONLY` to `"true"` if you only want the agent to read. Keep it `"false"` when you want
the agent to create or update Memory nodes.

## CLI Reference

Exit codes:

| Code | Meaning |
|---:|---|
| `0` | Success |
| `1` | Invalid arguments or runtime setup failure |
| `2` | One or more files failed to parse or ingest |

Options:

| Option | Short | Required | Default | Description |
|---|---|---:|---|---|
| `--source` | `-s` | yes |  | Root directory to scan. |
| `--bolt` | `-b` | yes |  | Memgraph Bolt URL, for example `bolt://localhost:7687`. |
| `--project` | `-P` | yes |  | Logical project name. Namespaces all graph nodes. |
| `--user` | `-u` | no | empty | Memgraph username. |
| `--pass` | `-p` | no | empty | Memgraph password. |
| `--threads` | `-t` | no | `1` | Parser threads. Each thread gets its own Bolt session. |
| `--wipe-project-code` |  | no | `false` | Delete this project's code graph before ingesting. |
| `--wipe-project-memories` |  | no | `false` | Delete this project's memory graph before ingesting. |
| `--apply-schema` |  | no | `false` | Apply Memgraph constraints and indexes before ingesting. |
| `--wipe-all` |  | no | `false` | Delete all data from Memgraph. |
| `--incremental` |  | no | `false` | Skip files whose last-modified timestamp matches the graph. |
| `--watch` | `-w` | no | `false` | Watch the source directory and re-ingest changes. |
| `--classpath` |  | no | empty | Platform-separated JAR paths for Java symbol resolution. |
| `--js-runtime-mode` |  | no | `managed` | `managed`, `system`, or `offline`. |
| `--js-runtime-cache` |  | no | `~/.cache/memgraph-ingester` | Cache directory for managed Node.js and TypeScript downloads. |
| `--js-node-version` |  | no | `22.11.0` | Pinned Node.js version for managed JS/TS parsing. |
| `--js-typescript-version` |  | no | `5.6.3` | Pinned TypeScript compiler package version. A leading `v` is accepted. |
| `--check-js-runtime` |  | no | `false` | Run a local JS runtime smoke check without connecting to Memgraph. |
| `--init-instructions` |  | no | `false` | Write or replace managed agent instructions and exit. Includes Code guidance by default. |
| `--instructions-agent` |  | no | `codex` | Agent preset: `codex`, `claude`, `gemini`, `github`, or `copilot`. Implies `--init-instructions` when explicitly provided. |
| `--instructions-file` |  | no | preset file | Instruction file to update. Overrides `--instructions-agent` and implies `--init-instructions`. |
| `--with-memories` |  | no | `false` | Include optional Memory workflow instructions when initializing agents. |
| `--help` |  | no |  | Print CLI help. |
| `--version` |  | no |  | Print CLI version. |

Parallel ingestion:

| Threads | Typical speedup | Bottleneck |
|---:|---|---|
| `1` | 1x | Sequential parse and write |
| `4` | about 2.5x to 3x | Write serialization starts |
| `8` | about 3x to 4x | Diminishing returns |
| `16+` | about 3x to 4x | Writes saturated |

Use 4 to 8 threads for large projects on most machines. Values higher than your CPU core count
rarely help.

## Graph Model

Memgraph Ingester creates two project-scoped graph roots:

- Code graph: `(:Project)-[:CONTAINS]->(:Language)-[:CONTAINS]->(:Code)`
- Memory graph: `(:Project)-[:HAS_MEMORY]->(:Memory)`

Every code and memory node uses the same `project` namespace. Code is grouped by language nodes
named `Java` and `Js`.

### Code Nodes

| Label | Identity | Key properties |
|---|---|---|
| `:Project` | `name` | Project anchor. |
| `:Language` | `(project, name)` | `Java` or `Js` group. |
| `:Code` | `(project, language)` | `sourceRoots`, `lastIngested`. |
| `:Package` | `(name, project, language)` | Package name. |
| `:File` | `(path, project)` | `lastModified`, `language`. |
| `:Class` | `(fqn, project)` | `name`, `packageName`, type flags, `language`, `kind`, `modulePath`, `framework`. |
| `:Interface` | `(fqn, project)` | `name`, `packageName`, `language`, `kind`, `modulePath`, `framework`. |
| `:Annotation` | `(fqn, project)` | `name`, `packageName`, `language`, `kind`, `modulePath`, `framework`. |
| `:Method` | `(signature, project)` | `name`, `returnType`, `visibility`, `isStatic`, `startLine`, `endLine`, `ownerFqn`, `ownerDisplayName`, `language`, `kind`. |
| `:Field` | `(fqn, project)` | `name`, `type`, `visibility`, `isStatic`, `language`, `kind`. |
| `:PendingCall` | `(project, callerSignature, calleeOwnerFqn, calleeName)` | Temporary owner/name call record resolved after ingestion. |

### Code Relationships

| Relationship | Meaning |
|---|---|
| `(:Project)-[:CONTAINS]->(:Language)-[:CONTAINS]->(:Code)` | Code graph anchor per language. |
| `(:Code)-[:CONTAINS]->(:Package \| :File)` | Top-level code membership. |
| `(:Package)-[:CONTAINS]->(:Class \| :Interface \| :Annotation)` | Package contents. |
| `(:File)-[:DEFINES]->(:Class \| :Interface \| :Annotation)` | Source location. |
| `(:Class)-[:EXTENDS]->(:Class)` | Class inheritance. |
| `(:Class)-[:IMPLEMENTS]->(:Interface)` | Interface implementation. |
| `(:Interface)-[:EXTENDS]->(:Interface)` | Interface inheritance. |
| `(:Class \| :Interface \| :Annotation)-[:DECLARES]->(:Method \| :Field)` | Type members. |
| `(:Method)-[:CALLS]->(:Method)` | Best-effort call graph. |
| `(:Method)-[:PENDING_CALL]->(:PendingCall)` | Deferred owner/name call awaiting unique target resolution. |
| `(:*)-[:ANNOTATED_WITH]->(:Annotation)` | Annotation or decorator usage. |

### Memory Nodes

Memory nodes are manually authored by agents or clients. They survive code re-ingestion when you do
not pass `--wipe-project-memories`.

Only the properties listed here should be used.

| Label | Identity | Allowed properties |
|---|---|---|
| `:Memory` | `project` | `project` |
| `:Decision` | `(id, project)` | `id`, `project`, `title`, `topic`, `status`, `rationale`, `consequences`, `createdAt`, `updatedAt` |
| `:ADR` | `(id, project)` | `id`, `project`, `number`, `title`, `status`, `context`, `decision`, `consequences`, `createdAt`, `updatedAt` |
| `:Rule` | `(id, project)` | `id`, `project`, `title`, `topic`, `severity`, `description`, `createdAt`, `updatedAt` |
| `:Context` | `(id, project)` | `id`, `project`, `title`, `topic`, `content`, `source`, `createdAt`, `updatedAt` |
| `:Finding` | `(id, project)` | `id`, `project`, `title`, `topic`, `type`, `status`, `summary`, `evidence`, `createdAt`, `updatedAt` |
| `:Task` | `(id, project)` | `id`, `project`, `title`, `status`, `priority`, `description`, `createdAt`, `updatedAt` |
| `:Risk` | `(id, project)` | `id`, `project`, `title`, `topic`, `severity`, `status`, `mitigation`, `createdAt`, `updatedAt` |
| `:Question` | `(id, project)` | `id`, `project`, `title`, `status`, `answer`, `createdAt`, `updatedAt` |
| `:Idea` | `(id, project)` | `id`, `project`, `title`, `topic`, `status`, `notes`, `createdAt`, `updatedAt` |
| `:CodeRef` | `(project, targetType, key)` | `project`, `targetType`, `key` |

Controlled values:

| Node | Property | Values |
|---|---|---|
| `:Decision` | `status` | `proposed`, `accepted`, `rejected`, `superseded` |
| `:ADR` | `status` | `draft`, `accepted`, `rejected`, `superseded` |
| `:Rule` | `severity` | `hard`, `soft`, `recommendation` |
| `:Finding` | `type` | `bug`, `perf`, `constraint`, `security` |
| `:Finding` | `status` | `open`, `resolved`, `obsolete` |
| `:Task` | `status` | `todo`, `doing`, `done`, `blocked`, `cancelled` |
| `:Task` | `priority` | `0` critical, `1` high, `2` medium, `3` low, `4` none |
| `:Risk` | `severity` | `low`, `medium`, `high`, `critical` |
| `:Risk` | `status` | `open`, `mitigated`, `accepted`, `obsolete` |
| `:Question` | `status` | `open`, `answered`, `obsolete` |
| `:Idea` | `status` | `proposed`, `accepted`, `rejected`, `obsolete` |

### Memory Relationships

| Relationship | Meaning |
|---|---|
| `(:Project)-[:HAS_MEMORY]->(:Memory)` | Memory graph anchor. |
| `(:Memory)-[:HAS_DECISION \| :HAS_ADR \| :HAS_RULE \| :HAS_CONTEXT]->(:*)` | Memory item ownership. |
| `(:Memory)-[:HAS_FINDING \| :HAS_TASK \| :HAS_RISK \| :HAS_QUESTION]->(:*)` | Memory item ownership. |
| `(:Memory)-[:HAS_IDEA]->(:Idea)` | Memory item ownership. |
| `(:Decision \| :ADR \| :Rule \| :Context \| :Finding \| :Task \| :Risk \| :Idea)-[:REFERS_TO]->(:CodeRef)` | Stable memory-to-code reference. |
| `(:CodeRef)-[:RESOLVES_TO]->(:Code \| :Package \| :File \| :Class \| :Interface \| :Annotation \| :Method \| :Field)` | Current code node resolved after ingestion. |

For `:CodeRef`, use `key: 'java'` or `key: 'js'` for `targetType: 'Code'`, and
`key: 'java:<package>'` or `key: 'js:<package>'` for `targetType: 'Package'`.

See [`doc/MEMORY.md`](doc/MEMORY.md) for Memory examples and Cypher recipes.
See [`doc/SCHEMA.md`](doc/SCHEMA.md) for the full graph model.

## Useful Queries

All classes in a project:

```cypher
MATCH (:Project {name: 'my-project'})-[:CONTAINS]->(:Language {name: 'Java'})
  -[:CONTAINS]->(:Code)-[:CONTAINS]->(:File)-[:DEFINES]->(c:Class)
WHERE c.isExternal = false
RETURN c.fqn
ORDER BY c.fqn;
```

Who calls a method:

```cypher
MATCH (caller:Method {project: 'my-project'})-[:CALLS]->(m:Method {project: 'my-project'})
WHERE m.signature CONTAINS 'Widget.save('
RETURN caller.signature
ORDER BY caller.signature;
```

Accepted decisions:

```cypher
MATCH (:Memory {project: 'my-project'})-[:HAS_DECISION]->(d:Decision {status: 'accepted'})
RETURN d.id, d.title, d.rationale
ORDER BY d.updatedAt DESC;
```

Memory linked to a file:

```cypher
MATCH (file:File {path: 'src/main/java/com/example/Widget.java', project: 'my-project'})
MATCH (memory {project: 'my-project'})-[:REFERS_TO]->(:CodeRef)-[:RESOLVES_TO]->(file)
RETURN labels(memory), memory.id, memory.title;
```

## Caveats

- JavaParser is not `javac`. It handles many projects well, but edge-case Java syntax and complex
  symbol resolution can still fail.
- Java `CALLS` edges are best-effort. Missing edges do not prove a call never happens.
- Use `--classpath` for better Java FQN and call-edge coverage.
- External Java parent types and annotations can appear as project-scoped nodes with
  `isExternal = true`.
- JS/TS `CALLS` edges are syntax-based and best-effort. Owner/name calls that cannot be
  resolved in-file are stored as `:PendingCall` records and retried after the batch. Direct owner
  methods are preferred, then the nearest superclass with exactly one matching method. Pending calls
  for a reingested JS/TS file are cleared before the file's current calls are stored.
- Raw JS/TS `:Class` queries include synthetic module owners and TypeScript enums. Filter
  `language = "js"` and `kind = "class"` when you only want JavaScript/TypeScript classes.
- Generated code is indexed only when its generated source directory is passed to `--source`.
- With `--threads > 1`, log order is non-deterministic. Graph writes are idempotent.

## Project Layout

```text
.
├── .github/workflows/
│   ├── maven.yml                               # Maven build/test workflow
│   └── native-binaries.yml                     # GraalVM native binaries + JS runtime smoke tests
├── doc/
│   ├── MEMORY.md                               # Memory graph usage guide and recipes
│   └── SCHEMA.md                               # Full code + memory graph schema reference
├── image/                                      # README banners and social preview assets
├── memgraph-platform/
│   └── docker-compose.yml                      # Local Memgraph + Lab stack
├── src/main/java/io/github/ousatov/tools/memgraph/
│   ├── AgentInstructionsInstaller.java         # Agent instruction install/replace support
│   ├── IngesterCli.java                        # picocli CLI entry point
│   ├── def/
│   │   └── Const.java                          # Shared parameter, label, and Cypher resource names
│   ├── exception/
│   │   └── ProcessingException.java            # Domain-level processing failure
│   ├── exe/
│   │   ├── CallEdgeWriter.java                 # Java call-edge extraction/writes
│   │   ├── CypherExecutor.java                 # Cypher execution and retry handling
│   │   ├── GraphWriter.java                    # Memgraph node/relationship writes
│   │   ├── IngestionMetrics.java               # Metrics snapshot model
│   │   ├── IngestionMetricsCollector.java      # Metrics collection from graph queries
│   │   ├── IngestionOrchestrator.java          # Ingestion, wipe, incremental, and watch workflow
│   │   ├── JavaLanguageAdapter.java            # JavaParser-backed Java ingestion adapter
│   │   ├── JavaTypeNames.java                  # Java type-name helpers
│   │   ├── JsAnalysis.java                     # Neutral JS analyzer records
│   │   ├── JsAnalyzer.java                     # Java wrapper around the bundled JS analyzer
│   │   ├── JsLanguageAdapter.java              # Node/TypeScript-backed JS/TS ingestion adapter
│   │   ├── LanguageAdapter.java                # Source-language adapter contract
│   │   ├── ManagedNodeRuntime.java             # Downloaded/cached Node.js runtime management
│   │   ├── ManagedTypescriptPackage.java       # Downloaded/cached TypeScript compiler management
│   │   ├── MetricsSnapshotValidator.java       # Metrics snapshot comparison helper
│   │   ├── MetricsValidationCli.java           # CLI for validating metrics snapshots
│   │   ├── ParseService.java                   # JavaParser setup and parsing
│   │   ├── RuntimeMode.java                    # JS runtime mode values
│   │   └── SourceLanguage.java                 # Supported source-language values
│   ├── schema/
│   │   └── Memgraph.java                       # Schema loader and global wipe helpers
│   └── vo/
│       ├── Method.java                         # Method graph payload
│       └── Settings.java                       # Ingestion settings payload
├── src/main/resources/
│   ├── META-INF/native-image/
│   │   └── io.github.ousatov-ua/memgraph-ingester/
│   │       ├── reflect-config.json             # GraalVM reflection metadata
│   │       └── resource-config.json            # GraalVM bundled resource patterns
│   ├── io/github/ousatov/tools/memgraph/cypher/
│   │   ├── action/                             # Shared upsert, delete, and resolve Cypher
│   │   │   └── Js/                             # JS/TS-specific cleanup Cypher
│   │   ├── metrics/                            # Metrics snapshot Cypher queries
│   │   ├── create-schema.cypher                # Constraints and indexes
│   │   ├── drop-schema.cypher                  # Schema teardown
│   │   └── wipe-all-data.cypher                # Full data wipe
│   ├── io/github/ousatov/tools/memgraph/js/
│   │   ├── js-analyzer-ast.cjs                 # TypeScript AST extraction helpers
│   │   ├── js-analyzer-paths.cjs               # JS/TS import and tsconfig path resolution
│   │   └── js-analyzer.cjs                     # Bundled TypeScript compiler-based JS/TS analyzer
│   └── simplelogger.properties                 # Runtime logging defaults
├── src/test/java/io/github/ousatov/tools/memgraph/
│   ├── AgentInstructionsInstallerTest.java     # Agent instruction installer tests
│   ├── CypherResourceTest.java                 # Bundled Cypher resource checks
│   ├── IngesterCliInstructionsTest.java        # CLI instruction-generation tests
│   ├── IngesterCliTest.java                    # CLI option and execution tests
│   ├── exception/                              # Domain exception tests
│   ├── extension/                              # Testcontainers Memgraph JUnit extension
│   ├── exe/                                    # Parser, writer, orchestrator, and memory ITs
│   └── schema/                                 # Schema loader tests
├── template/
│   ├── AI-memgraph-code-template.md            # Default code graph agent instructions
│   └── AI-memgraph-memory-template.md          # Optional Memory workflow agent instructions
├── .gitignore
├── LICENSE
├── pom.xml                                     # Maven build, release, and native-image configuration
└── README.md                                   # User documentation
```

## License

MIT. See [`LICENSE`](LICENSE).

## Acknowledgements

- [Evgeniy Voronyuk](https://github.com/brunneng) - testing support

## For Enthusiasts: Build It Yourself

You do not need this section to use the tool. Use the release downloads unless you want to hack on
the project.

### Build the shaded JAR

Requirements:

- Java 25 SDK.
- Maven 3.9+.

```bash
git clone https://github.com/ousatov-ua/memgraph-ingester.git
cd memgraph-ingester
mvn clean package -Pshade -DskipTests
```

Output:

```text
target/memgraph-ingester.jar
```

Run it:

```bash
java -jar target/memgraph-ingester.jar --help
```

### Validate a metrics snapshot

The metrics validator is opt-in. It is not bound to the normal build, test, release, or native
profiles. Run it after ingesting a project when you want to compare the current metrics table with
an expected snapshot file:

```bash
mvn compile exec:java@validate-metrics \
  -Dmetrics.expected=/path/to/expected-metrics.md \
  -Dmetrics.project=memgraph-ingester \
  -Dmetrics.bolt=bolt://localhost:7687
```

Use `-Dmetrics.user=...` and `-Dmetrics.pass=...` when the Bolt endpoint requires credentials.

### Build a native executable

Native builds use GraalVM Native Image and build for the OS where Maven runs.

```bash
mvn clean package -Pnative-macos -DskipTests
mvn clean package -Pnative-linux -DskipTests
mvn clean package -Pnative-windows -DskipTests
```

Use the profile that matches your operating system.

### Use as a Maven dependency

```xml
<dependency>
  <groupId>io.github.ousatov-ua</groupId>
  <artifactId>memgraph-ingester</artifactId>
  <version>10.0.1</version>
</dependency>
```
