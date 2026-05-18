**BLOCKING — Work incrementally** Before large reads or broad repository scans, summarize current
findings into a short plan.
If the context becomes large, compact and continue from the compacted summary.
Avoid reading huge generated files, target/classes, node_modules, build outputs, logs, or binaries.

## Knowledge graph (Memgraph)

Repo indexed under **`{{PROJECT_NAME}}`**. All queries MUST include `project: '{{PROJECT_NAME}}'`.

### Lookup order (MANDATORY)

1. **Memgraph** — structure, relationships, memory, metadata
2. **Source files** — line-level detail only
3. **grep/glob** — strings, comments, non-Java resources
4. **Other tools** — last resort

**BLOCKING — status/pending-work requests:** For any request about pending work, status,
open work, remaining work, or whether the task is done, run Memgraph Orientation queries first.
Then check Git if local file changes are relevant. Never answer from Git alone unless the user
explicitly asks for Git-only status.

**BLOCKING — before any task involving code changes:** run orientation queries (Rules, open
Findings, Context, active Tasks).
Empty results are valid — proceed normally. Skip if already run in this session. Use Context,
Decisions, and Rules as your context.

**BLOCKING — before any class/interface work:** query full hierarchy.

**BLOCKING — for any Java code investigation involving symbols,
fields, methods, callers, implementations, inheritance, annotations,
or type usages: query Memgraph first. This must happen before using any
other code-discovery, source-inspection, filesystem, search, IDE,
language-server, or runtime-introspection tool.**

**BLOCKING — before closing task:** save all findings/decisions as Memory nodes and verify.
**BLOCKING — on any Memory node lifecycle change (Task/Risk/Question/Decision/ADR/Idea):**
immediately update the status in Memgraph before proceeding.

**BLOCKING — when creating any Memory node that relates to code (
Task/Decision/Finding/Rule/ADR/Risk/Idea):**
always create and link at least one CodeRef via `REFERS_TO` → `RESOLVES_TO` pointing to
the relevant Class, Method, Field, Package, or File.

**BLOCKING — when need to check the body of a Method:**
query Memgraph first to find out `startLine` and `endLine`,
then read only those lines from the source file with `view_range: [startLine, endLine]` — do not
load the entire file.

When Memgraph returns no results, fall back to text search and state why.

---

### Memgraph query tool (MANDATORY)

**Strict rule — always follow this order when executing Cypher queries:**

1. **MCP Memgraph tool** — scan your available tools list for any tool whose name contains
   `memgraph` or `cypher` (e.g. `mcp_memgraph_query`). If found, use it exclusively — no shell
   commands needed.
2. **`mgconsole`** — fallback when no MCP tool is available; always use `--output-format=csv`.

   **BLOCKING — single `mgconsole` session per task:** after falling back to `mgconsole`, open one
   interactive `mgconsole --no_history` session and reuse that same session for every Memgraph query
   until the task is finished. Do not start a new `mgconsole` process per query. Close the session
   with `:quit` before the final response.

   **Preferred `mgconsole` fallback: one interactive session.** Start `mgconsole` once, run one
   Cypher statement at a time, end every statement with `;`, and exit with `:quit`.

   **BLOCKING — interactive TTY line submission:** when using interactive `mgconsole` through a
   TTY/tool session, submit lines with carriage return (`\r`). A multiline query pasted with
   LF-only (`\n`) may appear at the prompt but not execute even if it ends with `;`. If that
   happens, send an extra `\r`. Prefer one Cypher statement per write unless testing interactive
   behavior.

   ```bash
   mgconsole --host ${MG_HOST:-127.0.0.1} --port ${MG_PORT:-7687} ${MG_USER:+--username $MG_USER} ${MG_PASS:+--password $MG_PASS} --output-format=csv --no_history
   ```

   Use `--no_history`; sandboxed runs may fail when `mgconsole` tries to write
   `~/.memgraph/client_history`. Add `--verbose_execution_info` only for benchmarks because it adds
   output tokens. Interactive TTY output may include control sequences; for machine parsing, prefer
   MCP or one-off CSV.

   **One-off `mgconsole` fallback:**

   ```bash
   echo "<cypher>;" | mgconsole --host ${MG_HOST:-127.0.0.1} --port ${MG_PORT:-7687} ${MG_USER:+--username $MG_USER} ${MG_PASS:+--password $MG_PASS} --output-format=csv --no_history
   ```

   > **Empty output = 0 rows, not an error.** `mgconsole` emits no output when a query returns
   nothing — normal for orientation queries with no data yet.
   > If `mgconsole` is not in `$PATH`, locate it first:
   `which mgconsole || find /opt /usr/local -name mgconsole 2>/dev/null | head -1`
   > **Large result sets — paginate in Cypher** with `SKIP`/`LIMIT` (see *Pagination* section).
   Filter in `WHERE` first to reduce size. Never post-process with shell tools.
   > **Important** Remember to put semicolons after each statement.

State which tool was used when reporting query results.

---

### mgconsole query execution format (HARD RULE)

**CRITICAL: NEVER pass Cypher queries as direct arguments to mgconsole.**

**WRONG** — query as argument:

```bash
mgconsole [options] "MATCH (n) RETURN n;"
```

**CORRECT** — one-off non-interactive query via echo:

```bash
echo "MATCH (n) RETURN n;" | mgconsole [options]
```

Pattern:

```bash
echo "<cypher>" | mgconsole [options]
```

----

### Tagged-file requests (BLOCKING)

`@`-tagged paths (e.g. `@src/main/java`) do **NOT** bypass Memgraph. They hint at scope only.

**Before reading any tagged file or directory:**

1. Run orientation queries (Rules, Findings, Context, Tasks, Questions, Risks).
2. Run codebase-analysis queries below.
3. Only then open source files for line-level detail.

#### Codebase analysis (performance / SOLID / DRY / architecture)

```cypher
// Package boundaries
MATCH (p:Package {project: '{{PROJECT_NAME}}'})
RETURN p.name
  ORDER BY p.name;

// Class inventory
MATCH (p:Package {project: '{{PROJECT_NAME}}'})-[:CONTAINS]->(c:Class)
  WHERE NOT c.isExternal
RETURN p.name AS pkg, c.name AS cls, c.isAbstract, c.isFinal
  ORDER BY p.name, c.name;

// Cross-class call graph (coupling / SRP signal)
MATCH (a:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(b:Method {project: '{{PROJECT_NAME}}'})
WITH split(split(a.signature, '(')[0], '.') AS ap, split(split(b.signature, '(')[0], '.') AS bp
WITH ap[size(ap) - 2] AS ac, bp[size(bp) - 2] AS bc
  WHERE ac <> bc
RETURN ac + ' -> ' + bc AS edge, COUNT(*) AS n
  ORDER BY n DESC
  LIMIT 30;

// Method count per class (hotspot / SRP signal)
// ⚠️ Memgraph requires WITH before RETURN when mixing aggregation with node properties.
MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:DECLARES]->(m:Method)
  WHERE c.isExternal = false AND m.isSynthetic = false
WITH c.fqn AS cls, count(m) AS n
RETURN cls, n
  ORDER BY n DESC
  LIMIT 20;

// Interface implementors (LSP / DIP)
// ⚠️ Move isExternal filter into the node pattern — WHERE after OPTIONAL MATCH
//    causes "Unbound variable" in Memgraph when the clause follows a label scan.
MATCH (i:Interface {project: '{{PROJECT_NAME}}'})
OPTIONAL MATCH (c:Class {project: '{{PROJECT_NAME}}', isExternal: false})-[:IMPLEMENTS]->(i)
WITH i.fqn AS iface, collect(c.fqn) AS implementors
RETURN iface, implementors;

// Annotation usage (patterns / misuse)
// ⚠️ Label-less node patterns fail with relationships — use explicit label.
MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:ANNOTATED_WITH]->(a:Annotation)
  WHERE c.isExternal = false
WITH a.fqn AS ann, count(c) AS n
RETURN ann, n
  ORDER BY n DESC
  LIMIT 20;

// Non-static fields (field injection check)
MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:DECLARES]->(f:Field)
  WHERE NOT c.isExternal AND NOT f.isStatic
RETURN c.fqn AS cls, f.name, f.type, f.visibility
  ORDER BY c.fqn, f.name;
```

---

### Schema

#### Code nodes

| Label         | Key                    | Notable properties                                                                    |
|---------------|------------------------|---------------------------------------------------------------------------------------|
| `:Project`    | `name`                 | —                                                                                     |
| `:Package`    | `(name, project)`      | —                                                                                     |
| `:File`       | `(path, project)`      | `lastModified`                                                                        |
| `:Class`      | `(fqn, project)`       | `name`, `isAbstract`, `isEnum`, `isRecord`, `isFinal`, `isExternal`, `visibility`     |
| `:Interface`  | `(fqn, project)`       | `name`, `visibility`, `isFinal`, `isExternal`                                         |
| `:Annotation` | `(fqn, project)`       | `name`, `visibility`, `isExternal`                                                    |
| `:Method`     | `(signature, project)` | `name`, `returnType`, `visibility`, `isStatic`, `startLine`, `endLine`, `isSynthetic` |
| `:Field`      | `(fqn, project)`       | `name`, `type`, `visibility`, `isStatic`                                              |

#### Relationships

```
(:Project)-[:CONTAINS]->(:Code)-[:CONTAINS]->(:Package|:File)
(:Package)-[:CONTAINS]->(:Class|:Interface|:Annotation)
(:File)-[:DEFINES]->(:Class|:Interface|:Annotation)
(:Class)-[:EXTENDS]->(:Class), -[:IMPLEMENTS]->(:Interface)
(:Interface)-[:EXTENDS]->(:Interface)
(:Class|:Interface)-[:DECLARES]->(:Method|:Field)
(:Method)-[:CALLS]->(:Method)
(:*)-[:ANNOTATED_WITH]->(:Annotation)
```

#### Key caveats

- **`CALLS`** has no `project` — filter both ends.
- **`CALLS`/`ANNOTATED_WITH`** — best-effort; missing edges ≠ no relationship.
- **External nodes**: `isExternal = true`; exclude with `WHERE NOT n.isExternal`. When a class
  implements an external interface (e.g. a JDK or library type), that interface is stored as an
  external `:Interface` node (`isExternal = true`) — it **will not** appear in
  `WHERE NOT i.isExternal` queries, but the `IMPLEMENTS` edge and external node still exist and can
  be queried directly.
- **Annotation FQN**: non-JDK stored as simple name.
- **Constructors**: `name = '<init>'`. **Nested classes**: FQN uses `$` (e.g. `Outer$Inner`); stored
  in the **parent class's package** (not a sub-package); whether the class is `static` is not
  stored — infer from source if needed.
- **Record accessor methods**: auto-generated accessors (`name()`, `age()`, etc.) are stored with
  `isSynthetic = true`. They are **invisible** when filtering `WHERE NOT m.isSynthetic`. To see
  them, drop the filter or add `OR m.isSynthetic = true`. The record itself has `isRecord = true`. *
  *Records with no explicitly declared methods are completely absent from method-count queries** —
  if a record has 0 explicit methods, it will not appear in any `WHERE m.isSynthetic = false`
  result; this is correct behaviour, not an ingestion gap.
- **`DECLARES`**: always add label — `-[:DECLARES]->(m:Method)`.
- **`visibility`**: `"public"`, `"protected"`, `"private"`, `""` (package-private).
- **Aggregation + node property in RETURN**: Memgraph rejects `RETURN c.fqn, COUNT(m)` with *"
  Unbound variable"*. Always project via `WITH` first:
  `WITH c.fqn AS cls, COUNT(m) AS n RETURN cls, n`.
- **Label-less node patterns**: `MATCH (n {project: ...})-[:REL]->()` fails without an explicit
  label. Use `(n:Class {project: ...})` or `(n:Interface {project: ...})`.
- **`NOT property` vs `= false`**: prefer `c.isExternal = false` over `NOT c.isExternal` for
  reliability in multi-hop patterns.
- **`OPTIONAL MATCH` chaining on label scans**: chaining two or more `OPTIONAL MATCH` clauses after
  a label scan (e.g.
  `MATCH (c:Class) WHERE c.isExternal = false OPTIONAL MATCH ... OPTIONAL MATCH ...`) fails in
  Memgraph with *"Unbound variable"* when `collect()` aggregates appear in `RETURN`. **Fix:** move
  node filters into the MATCH pattern (`{isExternal: false}`), not `WHERE`, and never chain OPTIONAL
  MATCHes on a label scan — split into separate queries if multiple optional relationships are
  needed.
- **Implicit default constructors**: a class with no declared constructor gets a synthesized
  `<init>()` Method node (`isSynthetic=true`, `startLine=0`, `endLine=0`). This keeps
  `new ClassName()` CALLS edges alive through phantom cleanup. Invisible in
  `WHERE NOT m.isSynthetic` queries — add `OR m.isSynthetic = true` to include them.

---

### Memory schema

**Strict — no extra properties allowed.**

| Label       | Key props                      | Additional properties                                              |
|-------------|--------------------------------|--------------------------------------------------------------------|
| `:Memory`   | `project`                      | —                                                                  |
| `:Decision` | `id`, `project`                | `title`, `topic`, `status`, `rationale`, `consequences`            |
| `:ADR`      | `id`, `project`                | `number`, `title`, `status`, `context`, `decision`, `consequences` |
| `:Rule`     | `id`, `project`                | `title`, `topic`, `severity`, `description`                        |
| `:Context`  | `id`, `project`                | `title`, `topic`, `content`, `source`                              |
| `:Finding`  | `id`, `project`                | `title`, `topic`, `type`, `status`, `summary`, `evidence`          |
| `:Task`     | `id`, `project`                | `title`, `status`, `priority`, `description`                       |
| `:Risk`     | `id`, `project`                | `title`, `topic`, `severity`, `status`, `mitigation`               |
| `:Question` | `id`, `project`                | `title`, `status`, `answer`                                        |
| `:Idea`     | `id`, `project`                | `title`, `topic`, `status`, `notes`                                |
| `:CodeRef`  | `project`, `targetType`, `key` | —                                                                  |

All nodes also have `createdAt`, `updatedAt`.

**Controlled values:**

- Decision/ADR `status`: `proposed`|`accepted`|`rejected`|`superseded` (ADR also `draft`)
- Rule `severity`: `hard`|`soft`|`recommendation`
- Finding `type`: `bug`|`perf`|`constraint`|`security`
- Finding `status`: `open`|`resolved`|`obsolete`
- Task `status`: `todo`|`doing`|`done`|`blocked`|`cancelled`; `priority`: `0` (critical)|`1` (high)|
  `2` (medium)|`3` (low)|`4` (none)
- Risk `severity`: `low`|`medium`|`high`|`critical`; `status`: `open`|`mitigated`|`accepted`|
  `obsolete`
- Question `status`: `open`|`answered`|`obsolete`
- Idea `status`: `proposed`|`accepted`|`rejected`|`obsolete`

**ID format:** `DEC-`, `ADR-<n>-`, `RULE-`, `FIND-`, `TASK-`, `RISK-`, `CTX-`, `Q-`, `IDEA-` +
`<topic>-<name>`

**Links:**

```
(:Project)-[:HAS_MEMORY]->(:Memory)-[:HAS_*]->(:Decision|:ADR|:Rule|:Context|:Finding|:Task|:Risk|:Question|:Idea)
(:Decision|...)-[:REFERS_TO]->(:CodeRef)-[:RESOLVES_TO]->(:Code|:Package|:File|:Class|:Interface|:Annotation|:Method|:Field)
```

`CodeRef.key`: project name → `Code`, package → `Package`, path → `File`, FQN → types/fields,
signature → `Method`.

---

### Queries

#### Pagination

Always add `ORDER BY` for stable page boundaries. Use `SKIP`/`LIMIT` to paginate in Cypher — never
in shell post-processing.

```cypher
// Filter by class first, then paginate — page 1
MATCH (a:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(b:Method {project: '{{PROJECT_NAME}}'})
  WHERE a.signature CONTAINS 'ClassName.'
RETURN a.signature AS caller, b.signature AS callee
  ORDER BY caller
  SKIP 0
  LIMIT 200

// Page 2: increment SKIP by page size
... ORDER BY caller SKIP 200 LIMIT 200
```

Recommended page size: **200** for Method/CALLS queries, **100** for node-with-properties queries.
If the MCP tool saves results to a file due to size, re-query with a tighter `WHERE` filter first.

**Cypher aggregation rule:** Aggregation functions (`COUNT`, `SUM`, `collect`, etc.)
are only allowed in `WITH` and `RETURN` clauses. Never use aggregation functions
in `WHERE` or `ORDER BY` directly.

**Memgraph-specific hard rule:** In any query that uses aggregation, do not
`RETURN` or `ORDER BY` node-property expressions directly, such as `c.fqn`,
`m.signature`, or `iface.fqn`. First project every grouping key and aggregate into
aliases with `WITH`, then `RETURN`, `ORDER BY`, filter, or paginate only by those
aliases.

  ```cypher
  // WRONG — aggregate plus direct node-property expressions:
  MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:IMPLEMENTS]->(iface:Interface)
  RETURN c.fqn, collect(DISTINCT iface.fqn) AS ifaces
    ORDER BY c.fqn

  // WRONG — aggregate directly in ORDER BY:
  MATCH (a:Method)-[:CALLS]->(b:Method)
    WHERE a.signature CONTAINS 'ClassName.'
  RETURN a.signature, b.signature
    ORDER BY COUNT(*) DESC

  // CORRECT — alias grouping keys and aggregates first:
  MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:IMPLEMENTS]->(iface:Interface)
  WITH c.fqn AS classFqn, collect(DISTINCT iface.fqn) AS ifaces
  RETURN classFqn, ifaces
    ORDER BY classFqn

  // CORRECT — aggregate first, then sort by the aggregate alias:
  MATCH (a:Method)-[:CALLS]->(b:Method)
    WHERE a.signature CONTAINS 'ClassName.'
  WITH a.signature AS caller, b.signature AS callee, COUNT(*) AS cnt
  RETURN caller, callee
    ORDER BY cnt DESC
    LIMIT 20
  ```

---

#### Orientation (run at task start)

If MCP is unavailable and `mgconsole` is used, start one interactive `mgconsole --no_history`
session and run these statements one at a time. Empty output = 0 rows (normal).

```cypher

MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_RULE]->(r:Rule)
RETURN r.id, r.severity, r.description
  ORDER BY r.severity;

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

#### Hierarchy (before touching any class/interface)

```cypher
// Direct hierarchy
MATCH (c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})
OPTIONAL MATCH (c)-[:EXTENDS]->(parent:Class)
OPTIONAL MATCH (c)-[:IMPLEMENTS]->(iface:Interface)
OPTIONAL MATCH (child:Class)-[:EXTENDS]->(c)
RETURN c.fqn, collect(DISTINCT parent.fqn) AS parents,
       collect(DISTINCT iface.fqn) AS ifaces, collect(DISTINCT child.fqn) AS children;

// Full ancestor chain (reverse for descendants)
MATCH path = (c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})
  -[:EXTENDS*]->(a:Class {project: '{{PROJECT_NAME}}'})
RETURN [n IN nodes(path) | n.fqn];
```

#### Code search

**Always include `m.startLine, m.endLine` when fetching methods** — use these with
`view_range: [startLine, endLine]` to read only the relevant lines from source files instead of
loading the entire file.

```cypher
// Methods / fields of a class — include line numbers for targeted view_range reads
MATCH (c:Class {fqn: '...', project: '{{PROJECT_NAME}}'})-[:DECLARES]->(m:Method)
RETURN m.signature, m.visibility, m.returnType, m.startLine, m.endLine
  ORDER BY m.name;

// Callers of a method
MATCH (caller:Method {project: '{{PROJECT_NAME}}'})
        -[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
  WHERE callee.signature CONTAINS 'MyClass.myMethod('
RETURN caller.signature;

// Cross-class dependencies
MATCH (caller:Method {project: '{{PROJECT_NAME}}'})
        -[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
WITH split(split(caller.signature, '(')[0], '.') AS cp,
     split(split(callee.signature, '(')[0], '.') AS tp
WITH cp[size(cp) - 2] AS cc, tp[size(tp) - 2] AS tc
  WHERE cc <> tc
RETURN cc + ' -> ' + tc AS edge, COUNT(*) AS cnt
  ORDER BY cnt DESC;
```

Signature format: `pkg.ClassName.methodName(fully.qualified.ParamType, ...)`. Constructors:
`<init>`.

---

### Task lifecycle

```cypher

MATCH (t:Task {id: 'TASK-<id>', project: '{{PROJECT_NAME}}'})
SET t.status = 'doing', t.updatedAt = datetime();
// on complete:
MATCH (t:Task {id: 'TASK-<id>', project: '{{PROJECT_NAME}}'})
SET t.status = 'done', t.updatedAt = datetime();
```

---

### Saving memory (mandatory before the task closes)

Memory is not a changelog. Store only information that improves future decisions,
investigations, or implementation work. Do not create memory nodes just because a
file changed; routine edits are represented by the git diff, tests, and final
response.

| Trigger                             | Node                      |
|-------------------------------------|---------------------------|
| Design/implementation choice        | `:Decision` (`accepted`)  |
| Architectural direction             | `:ADR`                    |
| Future rule/constraint              | `:Rule`                   |
| Bug / perf issue / wrong assumption | `:Finding` (`bug`/`perf`) |
| Codebase limitation                 | `:Finding` (`constraint`) |
| Durable reusable project knowledge  | `:Context`                |
| Unfinished work / follow-up         | `:Task` (`todo`)          |
| Open question                       | `:Question` (`open`)      |
| New/discovered risk                 | `:Risk` (`open`)          |

#### Context policy

Use `:Context` as reusable knowledge for future sessions, not as a history record
of edits. Context should answer: "What should a future agent know before working
in this area?"

Create or update Context only when the session discovers durable knowledge such
as:

- stable subsystem behavior not obvious from source;
- operational constraints, caveats, or recurring failure modes;
- summarized knowledge from a completed investigation;
- ADR background that remains useful outside the ADR itself.

Prefer updating an existing summarized Context for the topic instead of creating
timestamped or session-scoped records. Use IDs like
`CTX-<topic>-summary`, `CTX-<subsystem>-constraints`, or
`CTX-<workflow>-notes`. Keep `content` concise and current; replace obsolete
details rather than appending a log.

Do not create Context for:

- routine file/schema/config/Cypher modifications;
- a list of files changed;
- test commands run;
- information already captured better as a Decision, ADR, Finding, Task, Risk,
  Question, or Rule.

When an ADR is created, create or update Context only if the background knowledge
will be useful independently of the ADR. The ADR remains the authoritative
decision record.

```cypher
// Create (adapt label/id/HAS_* for other types)
MERGE (m:Memory {project: '{{PROJECT_NAME}}'})
MERGE (d:Decision {id: 'DEC-<topic>-<name>', project: '{{PROJECT_NAME}}'})
SET d.title = '<title>', d.topic = '<topic>', d.status = 'accepted', d.rationale = '<rationale>',
d.createdAt = coalesce(d.createdAt, datetime()), d.updatedAt = datetime()
MERGE (m)-[:HAS_DECISION]->(d);

// Link to code — MUST be a separate query from the memory-node creation above.
// Use MATCH (not MERGE) for code nodes: they are pre-existing and have unique constraints.
// Merging them inline causes "unique constraint violation" errors.
MATCH (c:Class {fqn: '<fqn>', project: '{{PROJECT_NAME}}'})
MERGE (ref:CodeRef {project: '{{PROJECT_NAME}}', targetType: 'Class', key: '<fqn>'})
MERGE (d)-[:REFERS_TO]->(ref)
MERGE (ref)-[:RESOLVES_TO]->(c);

// Verify (must return rows; retry if empty)
MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[r]->(n)
  WHERE n.updatedAt >= datetime() - duration('PT5M')
RETURN type(r) AS rel, n.id AS id, labels(n) AS type;
```

```cypher
// Update summarized Context only when reusable knowledge was learned.
MERGE (m:Memory {project: '{{PROJECT_NAME}}'})
MERGE (c:Context {id: 'CTX-<topic>-summary', project: '{{PROJECT_NAME}}'})
SET c.title = '<summary title>', c.topic = '<topic>',
c.content = '<concise current knowledge useful to future sessions>',
c.source = '<why this knowledge is trustworthy>',
c.createdAt = coalesce(c.createdAt, datetime()), c.updatedAt = datetime()
MERGE (m)-[:HAS_CONTEXT]->(c);
```

---

### Staleness

```cypher

MATCH (c:Code {project: '{{PROJECT_NAME}}'})
RETURN c.lastIngested;
```

> `:Code` is the intermediary node between `:Project` and `:Package`/`:File` — it is **not** listed
> in the schema table above but exists in the graph. `lastIngested` is a Unix-epoch **microseconds**
> integer (e.g. `1778314313032240`).

> Memory is not logs. Store only what improves future decisions.
