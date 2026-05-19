## Incremental Work

**BLOCKING:** Before large reads or broad repository scans, summarize current findings into a short plan.
If the context becomes large, compact and continue from the compacted summary.
Avoid huge generated files, `target/classes`, `node_modules`, build outputs, logs, and binaries.

## Knowledge Graph

Repo is indexed in Memgraph as **`{{PROJECT_NAME}}`**. Every query MUST include
`project: '{{PROJECT_NAME}}'`.

### Lookup Order

Use this order for repository knowledge:

1. **Memgraph** for structure, relationships, memory, and metadata.
2. **Source files** for line-level detail only.
3. **grep/glob** for strings, comments, and non-Java resources.
4. **Other tools** only as a last resort.

When Memgraph returns no relevant rows, fall back to text search and state why.

### Mandatory Triggers

- **Status/pending-work requests:** run Orientation queries first, then check Git if local changes are relevant. Never answer from Git alone unless the user explicitly asks for Git-only status.
- **Orientation reuse:** Orientation queries are session-scoped. If they were already run for `{{PROJECT_NAME}}` in this assistant session, reuse those results for follow-up work and skip rerunning them unless memory was changed, the user asks for a refresh, or the task scope is unrelated.
- **Code changes:** before any code-change task, run Orientation queries for Rules, open Findings, Context, active Tasks, open Questions, and open Risks. Empty results are valid. Skip only if already run in this session.
- **Class/interface work:** before touching a class or interface, query its full hierarchy.
- **Java symbol work:** for investigations involving symbols, fields, methods, callers, implementations, inheritance, annotations, or type usages, query Memgraph before source inspection, filesystem search, IDE/LSP, or runtime introspection.
- **Method body reads:** first query `startLine` and `endLine`, then read only that source range.
- **Task close:** save durable findings/decisions as Memory nodes and verify them.
- **Memory lifecycle changes:** immediately update Task/Risk/Question/Decision/ADR/Idea status in Memgraph before proceeding.
- **Code-related memory:** when creating Task/Decision/Finding/Rule/ADR/Risk/Idea nodes related to code, create at least one `CodeRef` and link `(:MemoryNode)-[:REFERS_TO]->(:CodeRef)-[:RESOLVES_TO]->(:Code|:Package|:File|:Class|:Interface|:Annotation|:Method|:Field)`.

## Memgraph Access

### Tool Order

For Cypher queries:

1. Use an MCP tool whose name contains `memgraph` or `cypher` if available.
2. Otherwise, use `mgconsole` with `--no_history --output-format=csv`.

Report which query tool was used.

### `mgconsole` Rules

**BLOCKING:** Use one interactive `mgconsole --no_history` session per task and reuse it for all Memgraph queries until the task is finished. Close it with `:quit` before final response.

Start command:

```bash
mgconsole --host ${MG_HOST:-127.0.0.1} --port ${MG_PORT:-7687} ${MG_USER:+--username $MG_USER} ${MG_PASS:+--password $MG_PASS} --output-format=csv --no_history
```

Interactive TTY submission:
- Run one Cypher statement at a time.
- End every statement with `;`.
- Submit lines with carriage return (`\r`); LF-only multiline paste may not execute.
- Empty output means 0 rows, not an error.
- Large results must be paginated in Cypher with `ORDER BY`, `SKIP`, and `LIMIT`; do not post-process with shell tools.
- If `mgconsole` is missing, locate it with `which mgconsole || find /opt /usr/local -name mgconsole 2>/dev/null | head -1`.

**HARD RULE:** Never pass Cypher as a direct `mgconsole` argument.

```bash
# Wrong
mgconsole [options] "MATCH (n) RETURN n;"

# Correct non-interactive form, only when a single-query workflow is explicitly appropriate
echo "MATCH (n) RETURN n;" | mgconsole [options]
```

## Tagged Files

`@`-tagged paths hint at scope only; they do not bypass Memgraph.

Before reading any tagged file or directory:

1. Run Orientation queries.
2. Run Codebase Analysis queries.
3. Then open source files for line-level detail.

## Codebase Analysis Queries

```cypher
// Package boundaries
MATCH (p:Package {project: '{{PROJECT_NAME}}'})
RETURN p.name ORDER BY p.name;

// Class inventory
MATCH (p:Package {project: '{{PROJECT_NAME}}'})-[:CONTAINS]->(c:Class)
WHERE c.isExternal = false
RETURN p.name AS pkg, c.name AS cls, c.isAbstract, c.isFinal
ORDER BY p.name, c.name;

// Cross-class call graph
MATCH (a:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(b:Method {project: '{{PROJECT_NAME}}'})
WITH split(split(a.signature, '(')[0], '.') AS ap, split(split(b.signature, '(')[0], '.') AS bp
WITH ap[size(ap) - 2] AS ac, bp[size(bp) - 2] AS bc
WHERE ac <> bc
WITH ac + ' -> ' + bc AS edge, COUNT(*) AS n
RETURN edge, n ORDER BY n DESC LIMIT 30;

// Method-count hotspots
MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:DECLARES]->(m:Method)
WHERE c.isExternal = false AND m.isSynthetic = false
WITH c.fqn AS cls, COUNT(m) AS n
RETURN cls, n ORDER BY n DESC LIMIT 20;

// Interface implementors
MATCH (i:Interface {project: '{{PROJECT_NAME}}'})
OPTIONAL MATCH (c:Class {project: '{{PROJECT_NAME}}', isExternal: false})-[:IMPLEMENTS]->(i)
WITH i.fqn AS iface, collect(c.fqn) AS implementors
RETURN iface, implementors ORDER BY iface;

// Annotation usage
MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:ANNOTATED_WITH]->(a:Annotation)
WHERE c.isExternal = false
WITH a.fqn AS ann, COUNT(c) AS n
RETURN ann, n ORDER BY n DESC LIMIT 20;

// Non-static fields
MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:DECLARES]->(f:Field)
WHERE c.isExternal = false AND f.isStatic = false
RETURN c.fqn AS cls, f.name, f.type, f.visibility
ORDER BY c.fqn, f.name;
```

## Schema

### Code Nodes

| Label         | Key                    | Notable properties                                                                    |
|---------------|------------------------|---------------------------------------------------------------------------------------|
| `:Project`    | `name`                 | -                                                                                     |
| `:Code`       | `project`              | `lastIngested`                                                                        |
| `:Package`    | `(name, project)`      | -                                                                                     |
| `:File`       | `(path, project)`      | `lastModified`                                                                        |
| `:Class`      | `(fqn, project)`       | `name`, `isAbstract`, `isEnum`, `isRecord`, `isFinal`, `isExternal`, `visibility`     |
| `:Interface`  | `(fqn, project)`       | `name`, `visibility`, `isFinal`, `isExternal`                                         |
| `:Annotation` | `(fqn, project)`       | `name`, `visibility`, `isExternal`                                                    |
| `:Method`     | `(signature, project)` | `name`, `returnType`, `visibility`, `isStatic`, `startLine`, `endLine`, `isSynthetic` |
| `:Field`      | `(fqn, project)`       | `name`, `type`, `visibility`, `isStatic`                                              |

### Code Relationships

```text
(:Project)-[:CONTAINS]->(:Code)-[:CONTAINS]->(:Package|:File)
(:Package)-[:CONTAINS]->(:Class|:Interface|:Annotation)
(:File)-[:DEFINES]->(:Class|:Interface|:Annotation)
(:Class)-[:EXTENDS]->(:Class)
(:Class)-[:IMPLEMENTS]->(:Interface)
(:Interface)-[:EXTENDS]->(:Interface)
(:Class|:Interface)-[:DECLARES]->(:Method|:Field)
(:Method)-[:CALLS]->(:Method)
(:*)-[:ANNOTATED_WITH]->(:Annotation)
```

### Query Caveats

- `CALLS` has no `project`; filter both endpoints.
- `CALLS` and `ANNOTATED_WITH` are best-effort; missing edges do not prove no relationship.
- External nodes use `isExternal = true`. External interfaces implemented by project classes still have `IMPLEMENTS` edges, but are excluded by internal-interface filters.
- Non-JDK annotation FQNs may be stored as simple names.
- Constructors use `name = '<init>'`.
- Nested class FQNs use `$` and are stored in the parent class package; static-ness is not stored.
- Record accessor methods are synthetic. Drop the synthetic filter when accessors matter. Records with no explicit methods do not appear in non-synthetic method-count results.
- Always label `DECLARES` targets, e.g. `-[:DECLARES]->(m:Method)`.
- `visibility` values are `"public"`, `"protected"`, `"private"`, or `""` for package-private.
- Prefer `c.isExternal = false` over `NOT c.isExternal` in multi-hop patterns.
- Label-less relationship patterns such as `MATCH (n {project: ...})-[:REL]->()` fail; use explicit labels.
- Aggregation must be in `WITH` or `RETURN`; never use aggregate functions directly in `WHERE` or `ORDER BY`.
- When a query uses aggregation, first alias grouping keys and aggregate values with `WITH`; then `RETURN`, `ORDER BY`, filter, or paginate by aliases only.
- Avoid chaining multiple `OPTIONAL MATCH` clauses after label scans when aggregates appear. Move filters into node patterns and split into separate queries if needed.
- Implicit default constructors are synthetic `<init>()` methods with `startLine=0`, `endLine=0`.

## Standard Queries

### Pagination

Use stable ordering and paginate in Cypher:

```cypher
MATCH (a:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(b:Method {project: '{{PROJECT_NAME}}'})
WHERE a.signature CONTAINS 'ClassName.'
WITH a.signature AS caller, b.signature AS callee
RETURN caller, callee
ORDER BY caller
SKIP 0
LIMIT 200;
```

Recommended page sizes: 200 for Method/CALLS queries, 100 for node-with-properties queries.
If a tool saves results to a file because the result is large, re-query with tighter `WHERE` filters.

### Orientation

Run at task start when required:

```cypher
MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_RULE]->(r:Rule)
RETURN r.id, r.severity, r.description ORDER BY r.severity;

MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_FINDING]->(f:Finding)
WHERE f.status = 'open'
RETURN f.id, f.type, f.summary;

MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_CONTEXT]->(c:Context)
RETURN c.id, c.content, c.source;

MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_TASK]->(t:Task)
WHERE t.status IN ['todo', 'doing', 'blocked']
RETURN t.id, t.title, t.status, t.priority
ORDER BY t.priority, t.status;

MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_QUESTION]->(q:Question)
WHERE q.status = 'open'
RETURN q.id, q.title;

MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_RISK]->(r:Risk)
WHERE r.status = 'open'
RETURN r.id, r.title, r.severity;
```

### Hierarchy

Run before class/interface work:

```cypher
MATCH (c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})
OPTIONAL MATCH (c)-[:EXTENDS]->(parent:Class)
OPTIONAL MATCH (c)-[:IMPLEMENTS]->(iface:Interface)
OPTIONAL MATCH (child:Class)-[:EXTENDS]->(c)
WITH c.fqn AS classFqn, collect(DISTINCT parent.fqn) AS parents,
     collect(DISTINCT iface.fqn) AS ifaces, collect(DISTINCT child.fqn) AS children
RETURN classFqn, parents, ifaces, children;

MATCH path = (c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})-[:EXTENDS*]->(a:Class {project: '{{PROJECT_NAME}}'})
RETURN [n IN nodes(path) | n.fqn] AS ancestors;
```

### Code Search

Always include method line numbers when fetching methods.

```cypher
MATCH (c:Class {fqn: '...', project: '{{PROJECT_NAME}}'})-[:DECLARES]->(m:Method)
RETURN m.signature, m.visibility, m.returnType, m.startLine, m.endLine
ORDER BY m.name;

MATCH (caller:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
WHERE callee.signature CONTAINS 'MyClass.myMethod('
RETURN caller.signature;

MATCH (caller:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
WITH split(split(caller.signature, '(')[0], '.') AS cp, split(split(callee.signature, '(')[0], '.') AS tp
WITH cp[size(cp) - 2] AS cc, tp[size(tp) - 2] AS tc
WHERE cc <> tc
WITH cc + ' -> ' + tc AS edge, COUNT(*) AS cnt
RETURN edge, cnt ORDER BY cnt DESC;
```

Signature format: `pkg.ClassName.methodName(fully.qualified.ParamType, ...)`.
Constructors use `<init>`.

### Staleness

```cypher
MATCH (c:Code {project: '{{PROJECT_NAME}}'})
RETURN c.lastIngested;
```

`lastIngested` is Unix epoch microseconds.

## Memory Schema

**Strict:** no extra properties.

| Label       | Key props                      | Additional properties                                              |
|-------------|--------------------------------|--------------------------------------------------------------------|
| `:Memory`   | `project`                      | -                                                                  |
| `:Decision` | `id`, `project`                | `title`, `topic`, `status`, `rationale`, `consequences`            |
| `:ADR`      | `id`, `project`                | `number`, `title`, `status`, `context`, `decision`, `consequences` |
| `:Rule`     | `id`, `project`                | `title`, `topic`, `severity`, `description`                        |
| `:Context`  | `id`, `project`                | `title`, `topic`, `content`, `source`                              |
| `:Finding`  | `id`, `project`                | `title`, `topic`, `type`, `status`, `summary`, `evidence`          |
| `:Task`     | `id`, `project`                | `title`, `status`, `priority`, `description`                       |
| `:Risk`     | `id`, `project`                | `title`, `topic`, `severity`, `status`, `mitigation`               |
| `:Question` | `id`, `project`                | `title`, `status`, `answer`                                        |
| `:Idea`     | `id`, `project`                | `title`, `topic`, `status`, `notes`                                |
| `:CodeRef`  | `project`, `targetType`, `key` | -                                                                  |

All memory nodes have `createdAt` and `updatedAt`.

Controlled values:
- Decision/ADR `status`: `proposed`, `accepted`, `rejected`, `superseded`; ADR also `draft`.
- Rule `severity`: `hard`, `soft`, `recommendation`.
- Finding `type`: `bug`, `perf`, `constraint`, `security`; `status`: `open`, `resolved`, `obsolete`.
- Task `status`: `todo`, `doing`, `done`, `blocked`, `cancelled`; `priority`: `0` critical, `1` high, `2` medium, `3` low, `4` none.
- Risk `severity`: `low`, `medium`, `high`, `critical`; `status`: `open`, `mitigated`, `accepted`, `obsolete`.
- Question `status`: `open`, `answered`, `obsolete`.
- Idea `status`: `proposed`, `accepted`, `rejected`, `obsolete`.

ID prefixes: `DEC-`, `ADR-<n>-`, `RULE-`, `FIND-`, `TASK-`, `RISK-`, `CTX-`, `Q-`, `IDEA-` + `<topic>-<name>`.

Memory links:

```text
(:Project)-[:HAS_MEMORY]->(:Memory)-[:HAS_*]->(:Decision|:ADR|:Rule|:Context|:Finding|:Task|:Risk|:Question|:Idea)
(:Decision|...)-[:REFERS_TO]->(:CodeRef)-[:RESOLVES_TO]->(:Code|:Package|:File|:Class|:Interface|:Annotation|:Method|:Field)
```

`CodeRef.key`: project name for `Code`, package name for `Package`, path for `File`, FQN for types/fields, signature for `Method`.

## Memory Policy

Memory is not a changelog. Store only information useful for future decisions, investigations, or implementation work.
Do not create memory nodes just because files changed; routine edits belong in Git diff, tests, and final response.
When the user asks you to do work, create or update a `Task` by default unless the user explicitly says not to create one. Reuse an existing matching `Task` when possible, mark it `doing` before starting, and close it as `done`, `blocked`, or `cancelled` when the work ends.

Create/update:
- `Decision` for design or implementation choices.
- `ADR` for architecture direction.
- `Rule` for future constraints.
- `Finding` for bugs, performance issues, wrong assumptions, or codebase limitations.
- `Context` for durable subsystem behavior, operational caveats, recurring failure modes, or reusable investigation summaries.
- `Task` for user-requested work and unfinished follow-up.
- `Question` for open questions.
- `Risk` for new or discovered risks.

For `Context`, prefer updating an existing summary such as `CTX-<topic>-summary`, `CTX-<subsystem>-constraints`, or `CTX-<workflow>-notes`.
Keep content concise and current. Do not use Context for routine file changes, test commands, or information better captured as Decision/ADR/Finding/Task/Risk/Question/Rule.

### Task Lifecycle

```cypher
MATCH (t:Task {id: 'TASK-<id>', project: '{{PROJECT_NAME}}'})
SET t.status = 'doing', t.updatedAt = datetime();

MATCH (t:Task {id: 'TASK-<id>', project: '{{PROJECT_NAME}}'})
SET t.status = 'done', t.updatedAt = datetime();
```

### Saving Memory

Create/update the memory node first:

```cypher
MERGE (m:Memory {project: '{{PROJECT_NAME}}'})
MERGE (d:Decision {id: 'DEC-<topic>-<name>', project: '{{PROJECT_NAME}}'})
SET d.title = '<title>', d.topic = '<topic>', d.status = 'accepted',
    d.rationale = '<rationale>', d.consequences = '<consequences>',
    d.createdAt = coalesce(d.createdAt, datetime()), d.updatedAt = datetime()
MERGE (m)-[:HAS_DECISION]->(d);
```

Link to code in a separate query. Use `MATCH` for existing code nodes; do not `MERGE` them inline because unique constraints can fail.

```cypher
MATCH (d:Decision {id: 'DEC-<topic>-<name>', project: '{{PROJECT_NAME}}'})
MATCH (c:Class {fqn: '<fqn>', project: '{{PROJECT_NAME}}'})
MERGE (ref:CodeRef {project: '{{PROJECT_NAME}}', targetType: 'Class', key: '<fqn>'})
MERGE (d)-[:REFERS_TO]->(ref)
MERGE (ref)-[:RESOLVES_TO]->(c);
```

Verify recent memory and its code link before the final response. Adapt `HAS_DECISION`,
`:Decision`, and `d` to the memory type just created:

```cypher
MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_DECISION]->(d:Decision)
WHERE d.updatedAt >= datetime() - duration('PT5M')
RETURN d.id AS id, labels(d) AS type;

MATCH (d:Decision {project: '{{PROJECT_NAME}}'})-[:REFERS_TO]->(ref:CodeRef)-[:RESOLVES_TO]->(target)
WHERE d.updatedAt >= datetime() - duration('PT5M')
RETURN d.id AS id, ref.targetType AS targetType, ref.key AS key, labels(target) AS targetLabels;
```

> `:Code` is the intermediary node between `:Project` and `:Package`/`:File` — it is **not** listed
> in the schema table above but exists in the graph. `lastIngested` is a Unix-epoch **microseconds**
> integer (e.g. `1778314313032240`).

> Memory is not logs. Store only what improves future decisions.
