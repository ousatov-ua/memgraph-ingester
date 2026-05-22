## Knowledge Graph

Repo is indexed in Memgraph as **`{{PROJECT_NAME}}`**. Every query MUST include
`project: '{{PROJECT_NAME}}'`.

### Lookup Order

Use this order for repository knowledge:

1. **Memgraph** for structure, relationships, memory, and metadata.
2. **Source files** for line-level detail only.
3. **grep/glob** for strings, comments, templates, and resources not represented in the graph.
4. **Other tools** only as a last resort.

When Memgraph returns no relevant rows, fall back to text search and state why.

### Mandatory Triggers

- **Status/pending-work requests:** run Orientation queries first, then check Git if local changes are relevant. Never answer from Git alone unless the user explicitly asks for Git-only status.
- **Orientation reuse:** Orientation queries are session-scoped. If they were already run for `{{PROJECT_NAME}}` in this assistant session, reuse those results for follow-up work and skip rerunning them unless memory was changed, the user asks for a refresh, or the task scope is unrelated.
- **Relationship refresh after edits:** if source files changed during the session, re-query Memgraph relationships before relying on earlier relationship results; live ingestion may make cached relationships stale.
- **Code changes:** before any code-change task, run Orientation queries for Rules, open Findings, Context, active Tasks, open Questions, and open Risks. Empty results are valid. Skip only if already run in this session.
- **Multi-step work tracking:** for multi-step implementation, debugging, refactoring, documentation, dependency, test, or coverage work, create/update a `Task` as `doing` before edits, even if you expect to finish in the same response.
- **Class/interface work:** before touching a class or interface, query its full hierarchy.
- **Symbol work:** for investigations involving symbols, fields, methods, callers, implementations, inheritance, decorators/annotations, imports, exports, or type usages, query Memgraph before source inspection, filesystem search, IDE/LSP, or runtime introspection. JavaScript/TypeScript CALLS edges are best-effort.
- **Method body reads:** first query `startLine` and `endLine`, then read only that source range.
- **Task close:** set any task you created or updated to `done`, `blocked`, or `cancelled` before final response and verify it. Also save durable findings/decisions when useful.
- **Memory lifecycle changes:** immediately update Task/Risk/Question/Decision/ADR/Idea status in Memgraph before proceeding.
- **Code-related memory:** when creating Task/Decision/Finding/Rule/ADR/Risk/Idea nodes related to code, create at least one `CodeRef` and link `(:Decision|:ADR|:Rule|:Context|:Finding|:Task|:Risk|:Question|:Idea)-[:REFERS_TO]->(:CodeRef)-[:RESOLVES_TO]->(:Code|:Package|:File|:Class|:Interface|:Annotation|:Method|:Field)`.

## Memgraph Access

### Tool Order

For Cypher queries:

1. Use an MCP tool whose name contains `memgraph` or `cypher` if available.
2. Otherwise, use `mgconsole` with `--no_history --output-format=csv`.

Report which query tool was used.

### `mgconsole` Rules

**BLOCKING:** Use one interactive `mgconsole --no_history --output-format=csv` session per task and reuse it for all Memgraph queries until the task is finished. Close it with `:quit` before final response.

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
MATCH (caller:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
WHERE caller.ownerFqn IS NOT NULL
  AND callee.ownerFqn IS NOT NULL
  AND caller.ownerFqn <> callee.ownerFqn
WITH caller.ownerDisplayName + ' -> ' + callee.ownerDisplayName AS edge, COUNT(*) AS n
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

| Label         | Key                    | Notable properties                                                                                                    |
|---------------|------------------------|-----------------------------------------------------------------------------------------------------------------------|
| `:Project`    | `name`                 | -                                                                                                                     |
| `:Code`       | `project`              | `lastIngested`                                                                                                        |
| `:Package`    | `(name, project)`      | -                                                                                                                     |
| `:File`       | `(path, project)`      | `lastModified`, `language`                                                                                            |
| `:Class`      | `(fqn, project)`       | `name`, `isAbstract`, `isEnum`, `isRecord`, `isFinal`, `isExternal`, `visibility`, `language`, `kind`, `modulePath`, `framework` |
| `:Interface`  | `(fqn, project)`       | `name`, `visibility`, `isFinal`, `isExternal`, `language`, `kind`, `modulePath`, `framework`                          |
| `:Annotation` | `(fqn, project)`       | `name`, `visibility`, `isExternal`, `language`, `kind`, `modulePath`, `framework`                                     |
| `:Method`     | `(signature, project)` | `name`, `ownerFqn`, `ownerDisplayName`, `returnType`, `visibility`, `isStatic`, `startLine`, `endLine`, `isSynthetic`, `language`, `kind` |
| `:Field`      | `(fqn, project)`       | `name`, `type`, `visibility`, `isStatic`, `language`, `kind`                                                          |
| `:PendingCall`| `(project, callerSignature, calleeOwnerFqn, calleeName)` | temporary owner/name call record resolved after ingestion                                      |

### Code Relationships

```text
(:Project)-[:CONTAINS]->(:Code)-[:CONTAINS]->(:Package|:File)
(:Package)-[:CONTAINS]->(:Class|:Interface|:Annotation)
(:File)-[:DEFINES]->(:Class|:Interface|:Annotation)
(:Class)-[:EXTENDS]->(:Class)
(:Class)-[:IMPLEMENTS]->(:Interface)
(:Interface)-[:EXTENDS]->(:Interface)
(:Class|:Interface|:Annotation)-[:DECLARES]->(:Method|:Field)
(:Method)-[:CALLS]->(:Method)
(:Method)-[:PENDING_CALL]->(:PendingCall)
(:*)-[:ANNOTATED_WITH]->(:Annotation)
```

### Query Caveats

- Code graph nodes may have optional `language` (`"java"` or `"javascript"`), `kind`, `modulePath`, and `framework` metadata. Older graphs may not have these properties.
- `CALLS` has no `project`; filter both endpoints.
- `CALLS` and `ANNOTATED_WITH` are best-effort; missing edges do not prove no relationship.
- JavaScript/TypeScript modules are represented as synthetic `:Class` owner nodes with `language = "javascript"` and `kind = "module"`. Top-level functions and variables are declared by that module owner.
- JavaScript/TypeScript classes reuse `:Class`; TypeScript enums reuse `:Class` with `isEnum = true` and `kind = "enum"`; TypeScript interfaces and type aliases reuse `:Interface`; decorators reuse `:Annotation` plus `ANNOTATED_WITH`.
- Angular decorators such as `Component`, `Directive`, `Injectable`, `NgModule`, and `Pipe` may set `framework = "angular"` on the decorated type.
- JavaScript/TypeScript function-valued class fields are emitted as callable `:Method` records and can also appear as `:Field` records.
- JavaScript/TypeScript class expressions assigned to variables are emitted as `:Class` nodes using the variable name. Relative imports that resolve to local source files can produce owner/name `CALLS` edges when the target owner has exactly one method with the imported name.
- JavaScript/TypeScript exported callable aliases such as `export { foo as bar }`, `export { foo as bar } from "./mod"`, and `export default foo` are emitted as graph-visible declarations for their public export names so deferred owner/name call resolution can match imports by exported name. Class re-export aliases are emitted as `:Class` nodes with constructor declarations so `new X()` imports from barrel modules can resolve through the alias.
- JavaScript/TypeScript namespace-qualified decorators preserve the namespace in the annotation FQN when the namespace import can be identified.
- JavaScript/TypeScript owner/name fallback calls can be stored as `:PendingCall` records during ingestion and resolved after the batch when the target owner declares exactly one method with that name. Unresolved or ambiguous pending calls can remain until later ingestion; pending calls for a reingested JS/TS file are cleared before the file's current calls are stored.
- JavaScript/TypeScript `CALLS` edges are syntax-only and intra-project best effort. Identifier calls resolve only when the local declaration name is unique. Property calls resolve only for known local receivers such as `this`, a local class, or `new LocalClass()`. Constructor calls from `new LocalClass()` and local function constructors resolve to explicit or synthesized signatures; imported or barrel class constructors use owner/name pending calls. Top-level IIFEs and callback bodies are traversed, while standalone nested functions are skipped. Unknown receivers, dynamic dispatch, dependency injection, framework templates, monkey-patching, and generated code can be missing.
- JavaScript/TypeScript `packageName` and module owner FQN values are synthetic, collision-safe encoded path identities with a `js.` prefix; they are not npm package names or raw filenames.
- Fully ingested `Method` nodes store `ownerFqn` and `ownerDisplayName`; prefer those properties for relationship summaries instead of parsing `signature` or traversing `DECLARES`.
- Placeholder callee `Method` nodes created during call-edge ingestion can lack owner metadata until the callee is ingested; phantom cleanup normally removes unresolved placeholders.
- External nodes use `isExternal = true`. External interfaces implemented by project classes still have `IMPLEMENTS` edges, but are excluded by internal-interface filters.
- Non-JDK annotation FQNs may be stored as simple names.
- Constructors use `name = '<init>'`.
- Nested class FQNs use `$` and are stored in the parent class package; static-ness is not stored.
- Record accessor methods are synthetic. Drop the synthetic filter when accessors matter. Records with no explicit methods do not appear in non-synthetic method-count results.
- Always label `DECLARES` targets, e.g. `-[:DECLARES]->(m:Method)`.
- `visibility` values are `"public"`, `"protected"`, `"private"`, or `""` for package-private.
- Prefer `c.isExternal = false` over `NOT c.isExternal` in multi-hop patterns.
- Prefer explicit labels in relationship patterns for precision and planner behavior.
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
OPTIONAL MATCH (c)-[:EXTENDS]->(parent:Class {project: '{{PROJECT_NAME}}'})
OPTIONAL MATCH (c)-[:IMPLEMENTS]->(iface:Interface {project: '{{PROJECT_NAME}}'})
OPTIONAL MATCH (child:Class {project: '{{PROJECT_NAME}}'})-[:EXTENDS]->(c)
WITH c.fqn AS classFqn, collect(DISTINCT parent.fqn) AS parents,
     collect(DISTINCT iface.fqn) AS ifaces, collect(DISTINCT child.fqn) AS children
RETURN classFqn, parents, ifaces, children;

MATCH path = (c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})-[:EXTENDS*]->(a:Class {project: '{{PROJECT_NAME}}'})
RETURN [n IN nodes(path) | n.fqn] AS ancestors;

MATCH path = (child:Class {project: '{{PROJECT_NAME}}'})-[:EXTENDS*]->(c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})
RETURN [n IN nodes(path) | n.fqn] AS descendants;

MATCH path = (c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})-[:EXTENDS*0..]->(:Class {project: '{{PROJECT_NAME}}'})-[:IMPLEMENTS]->(:Interface {project: '{{PROJECT_NAME}}'})-[:EXTENDS*0..]->(i:Interface {project: '{{PROJECT_NAME}}'})
RETURN DISTINCT i.fqn AS iface;

MATCH path = (i:Interface {fqn: 'com.example.MyInterface', project: '{{PROJECT_NAME}}'})-[:EXTENDS*]->(parent:Interface {project: '{{PROJECT_NAME}}'})
RETURN [n IN nodes(path) | n.fqn] AS interfaceAncestors;

MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:EXTENDS*0..]->(:Class {project: '{{PROJECT_NAME}}'})-[:IMPLEMENTS]->(:Interface {project: '{{PROJECT_NAME}}'})-[:EXTENDS*0..]->(i:Interface {fqn: 'com.example.MyInterface', project: '{{PROJECT_NAME}}'})
RETURN DISTINCT c.fqn AS implementor ORDER BY implementor;
```

### Code Search

Always include method line numbers when fetching methods.

```cypher
MATCH (c:Class {fqn: '...', project: '{{PROJECT_NAME}}'})-[:DECLARES]->(m:Method)
RETURN m.signature, m.ownerFqn, m.ownerDisplayName, m.visibility, m.returnType, m.startLine, m.endLine
ORDER BY m.name;

MATCH (caller:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
WHERE callee.signature CONTAINS 'MyClass.myMethod('
RETURN caller.signature;

MATCH (caller:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
WHERE caller.ownerFqn IS NOT NULL
  AND callee.ownerFqn IS NOT NULL
  AND caller.ownerFqn <> callee.ownerFqn
WITH caller.ownerDisplayName + ' -> ' + callee.ownerDisplayName AS edge, COUNT(*) AS cnt
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

Use `datetime()` for all property `createdAt` and `updatedAt` values; do not use `localDateTime()` for memory timestamps.

| Label       | Key props                      | Additional properties                                                                        |
|-------------|--------------------------------|----------------------------------------------------------------------------------------------|
| `:Memory`   | `project`                      | -                                                                                            |
| `:Decision` | `id`, `project`                | `title`, `topic`, `status`, `rationale`, `consequences`, `createdAt`, `updatedAt`            |
| `:ADR`      | `id`, `project`                | `number`, `title`, `status`, `context`, `decision`, `consequences`, `createdAt`, `updatedAt` |
| `:Rule`     | `id`, `project`                | `title`, `topic`, `severity`, `description`, `createdAt`, `updatedAt`                        |
| `:Context`  | `id`, `project`                | `title`, `topic`, `content`, `source`, `createdAt`, `updatedAt`                              |
| `:Finding`  | `id`, `project`                | `title`, `topic`, `type`, `status`, `summary`, `evidence`, `createdAt`, `updatedAt`          |
| `:Task`     | `id`, `project`                | `title`, `status`, `priority`, `description`, `createdAt`, `updatedAt`                       |
| `:Risk`     | `id`, `project`                | `title`, `topic`, `severity`, `status`, `mitigation`, `createdAt`, `updatedAt`               |
| `:Question` | `id`, `project`                | `title`, `status`, `answer`, `createdAt`, `updatedAt`                                        |
| `:Idea`     | `id`, `project`                | `title`, `topic`, `status`, `notes`, `createdAt`, `updatedAt`                                |
| `:CodeRef`  | `project`, `targetType`, `key` | -                                                                                            |

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

A `Task` is for durable work tracking, not every assistant action.
Create or update a `Task` for explicit tracking/follow-up requests, unfinished or blocked work, continuation of an existing Task, or multi-step implementation/debugging/refactoring/documentation/dependency/test/coverage work.
Create the `Task` even when the work is completed in the same response.
Do not create a `Task` for one-off read-only requests, status checks, simple lookups, or pure verification that does not modify files.
When unsure: if multi-step file edits are involved, create a `Task`; otherwise prefer not creating one.

Create/update:
- `Decision` for design or implementation choices.
- `ADR` for architecture direction.
- `Rule` for future constraints.
- `Finding` for bugs, performance issues, wrong assumptions, or codebase limitations.
- `Context` for durable subsystem behavior, operational caveats, recurring failure modes, or reusable investigation summaries.
- `Task` for durable tracked work, active implementation/debugging/documentation work, or unfinished follow-up.
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

> Memory is not logs. Store only what improves future decisions.
