## Knowledge graph (Memgraph)

Repo indexed under **`{{PROJECT_NAME}}`**. All queries MUST include `project: '{{PROJECT_NAME}}'`.

### Lookup order (MANDATORY)

1. **Memgraph** — structure, relationships, memory, metadata
2. **Source files** — line-level detail only
3. **grep/glob** — strings, comments, non-Java resources
4. **Other tools** — last resort

**BLOCKING — before any task involving code changes:** run orientation queries (Rules, open Findings, Context, active Tasks). Empty results are valid — proceed normally. Skip if already run in this session. **Read-only investigations (no code changes planned) may skip orientation.**

**BLOCKING — before any class/interface work:** query full hierarchy.  
**BLOCKING — for any Java code investigation (fields, methods, callers, type usages):**
Query Memgraph BEFORE opening source files or running grep/glob.

**BLOCKING — before closing task:** save all findings/decisions as Memory nodes and verify.
**BLOCKING — on any Memory node lifecycle change (Task/Risk/Question/Decision/ADR/Idea):**
immediately update the status in Memgraph before proceeding.

**BLOCKING — when creating any Memory node that relates to code (Task/Decision/Finding/Rule/ADR/Risk/Idea):** 
always create and link at least one CodeRef via `REFERS_TO` → `RESOLVES_TO` pointing to
the relevant Class, Method, Field, Package, or File.

When Memgraph returns no results, fall back to text search and state why.

---

### Memgraph query tool (MANDATORY)

**Strict rule — always follow this order when executing Cypher queries:**

1. **MCP Memgraph tool** — scan your available tools list for any tool whose name contains `memgraph` or `cypher` (e.g. `mcp_memgraph_query`). If found, use it exclusively — no shell commands needed.
2. **`mgconsole`** — fallback when no MCP tool is available; always use `--output-format=csv`. **One Cypher statement per `echo` pipe** — do not chain multiple statements with `;` in a single pipe. Multiple `echo … | mgq` lines in the same bash block are fine.

   If `mgq` is already on `PATH`, use it directly. If not, define it **once** at the top of the **first** bash block in the session, then use `mgq` in all subsequent bash blocks without any check:
   ```bash
   # First bash block only — define mgq if missing:
   mgq() { mgconsole --host ${MG_HOST:-127.0.0.1} --port ${MG_PORT:-7687} ${MG_USER:+--username $MG_USER} ${MG_PASS:+--password $MG_PASS} --output-format=csv "$@"; }
   echo "<single cypher query>" | mgq
   ```
   ```bash
   # All subsequent bash blocks — use mgq directly, no re-definition needed:
   echo "<single cypher query>" | mgq
   ```
   > **Empty output = 0 rows, not an error.** `mgconsole` emits no output (not even a header) when a query returns nothing — this is normal and expected for orientation queries that simply have no data yet.  
   > If `mgconsole` is not in `$PATH`, locate it first: `which mgconsole || find /opt /usr/local -name mgconsole 2>/dev/null | head -1`
   > **Large result sets**: queries returning many rows (e.g. all methods across all classes) may be saved to a temp file by the bash tool instead of shown inline. Avoid this by adding `LIMIT` or filtering by a specific class/package. If a temp file path is returned, read it with `head -100 <path>` or `cat <path>`.

State which tool was used when reporting query results.

---

### Tagged-file requests (BLOCKING)

`@`-tagged paths (e.g. `@src/main/java`) do **NOT** bypass Memgraph. They hint at scope only.

**Before reading any tagged file or directory:**
1. Run orientation queries (Rules, Findings, Context, Tasks, Questions, Risks).
2. Run codebase-analysis queries below.
3. Only then open source files for line-level detail.

#### Codebase analysis (performance / SOLID / DRY / architecture)

```cypher
// Package boundaries
MATCH (p:Package {project: '{{PROJECT_NAME}}'}) RETURN p.name ORDER BY p.name;

// Class inventory
MATCH (p:Package {project: '{{PROJECT_NAME}}'})-[:CONTAINS]->(c:Class)
WHERE NOT c.isExternal
RETURN p.name AS pkg, c.name AS cls, c.isAbstract, c.isFinal ORDER BY p.name, c.name;

// Cross-class call graph (coupling / SRP signal)
MATCH (a:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(b:Method {project: '{{PROJECT_NAME}}'})
WITH split(split(a.signature,'(')[0],'.') AS ap, split(split(b.signature,'(')[0],'.') AS bp
WITH ap[size(ap)-2] AS ac, bp[size(bp)-2] AS bc WHERE ac <> bc
RETURN ac+' -> '+bc AS edge, COUNT(*) AS n ORDER BY n DESC LIMIT 30;

// Method count per class (hotspot / SRP signal)
// ⚠️ Memgraph requires WITH before RETURN when mixing aggregation with node properties.
MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:DECLARES]->(m:Method)
WHERE c.isExternal = false AND m.isSynthetic = false
WITH c.fqn AS cls, COUNT(m) AS n
RETURN cls, n ORDER BY n DESC LIMIT 20;

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
WITH a.fqn AS ann, COUNT(c) AS n
RETURN ann, n ORDER BY n DESC LIMIT 20;

// Non-static fields (field injection check)
MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:DECLARES]->(f:Field)
WHERE NOT c.isExternal AND NOT f.isStatic
RETURN c.fqn AS cls, f.name, f.type, f.visibility ORDER BY c.fqn, f.name;
```

---

### Schema

#### Code nodes

| Label         | Key                    | Notable properties                                                              |
|---------------|------------------------|---------------------------------------------------------------------------------|
| `:Project`    | `name`                 | —                                                                               |
| `:Package`    | `(name, project)`      | —                                                                               |
| `:File`       | `(path, project)`      | `lastModified`                                                                  |
| `:Class`      | `(fqn, project)`       | `name`, `isAbstract`, `isEnum`, `isRecord`, `isFinal`, `isExternal`, `visibility` |
| `:Interface`  | `(fqn, project)`       | `name`, `visibility`, `isFinal`, `isExternal`                                   |
| `:Annotation` | `(fqn, project)`       | `name`, `visibility`, `isExternal`                                              |
| `:Method`     | `(signature, project)` | `name`, `returnType`, `visibility`, `isStatic`, `startLine`, `endLine`, `isSynthetic` |
| `:Field`      | `(fqn, project)`       | `name`, `type`, `visibility`, `isStatic`                                        |

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
- **External nodes**: `isExternal = true`; exclude with `WHERE NOT n.isExternal`. When a class implements an external interface (e.g. a JDK or library type), that interface is stored as an external `:Interface` node (`isExternal = true`) — it **will not** appear in `WHERE NOT i.isExternal` queries, but the `IMPLEMENTS` edge and external node still exist and can be queried directly.
- **Annotation FQN**: non-JDK stored as simple name.
- **Constructors**: `name = '<init>'`. **Nested classes**: FQN uses `$` (e.g. `Outer$Inner`); stored in the **parent class's package** (not a sub-package); whether the class is `static` is not stored — infer from source if needed.
- **Record accessor methods**: auto-generated accessors (`name()`, `age()`, etc.) are stored with `isSynthetic = true`. They are **invisible** when filtering `WHERE NOT m.isSynthetic`. To see them, drop the filter or add `OR m.isSynthetic = true`. The record itself has `isRecord = true`. **Records with no explicitly declared methods are completely absent from method-count queries** — if a record has 0 explicit methods, it will not appear in any `WHERE m.isSynthetic = false` result; this is correct behaviour, not an ingestion gap.
- **`DECLARES`**: always add label — `-[:DECLARES]->(m:Method)`.
- **`visibility`**: `"public"`, `"protected"`, `"private"`, `""` (package-private).
- **Aggregation + node property in RETURN**: Memgraph rejects `RETURN c.fqn, COUNT(m)` with *"Unbound variable"*. Always project via `WITH` first: `WITH c.fqn AS cls, COUNT(m) AS n RETURN cls, n`.
- **Label-less node patterns**: `MATCH (n {project: ...})-[:REL]->()` fails without an explicit label. Use `(n:Class {project: ...})` or `(n:Interface {project: ...})`.
- **`NOT property` vs `= false`**: prefer `c.isExternal = false` over `NOT c.isExternal` for reliability in multi-hop patterns.
- **`OPTIONAL MATCH` chaining on label scans**: chaining two or more `OPTIONAL MATCH` clauses after a label scan (e.g. `MATCH (c:Class) WHERE c.isExternal = false OPTIONAL MATCH ... OPTIONAL MATCH ...`) fails in Memgraph with *"Unbound variable"* when `collect()` aggregates appear in `RETURN`. **Fix:** move node filters into the MATCH pattern (`{isExternal: false}`), not `WHERE`, and never chain OPTIONAL MATCHes on a label scan — split into separate queries if multiple optional relationships are needed.

---

### Memory schema

**Strict — no extra properties allowed.**

| Label       | Key props                      | Additional properties                                                        |
|-------------|--------------------------------|------------------------------------------------------------------------------|
| `:Memory`   | `project`                      | —                                                                            |
| `:Decision` | `id`, `project`                | `title`, `topic`, `status`, `rationale`, `consequences`                      |
| `:ADR`      | `id`, `project`                | `number`, `title`, `status`, `context`, `decision`, `consequences`           |
| `:Rule`     | `id`, `project`                | `title`, `topic`, `severity`, `description`                                  |
| `:Context`  | `id`, `project`                | `title`, `topic`, `content`, `source`                                        |
| `:Finding`  | `id`, `project`                | `title`, `topic`, `type`, `status`, `summary`, `evidence`                    |
| `:Task`     | `id`, `project`                | `title`, `status`, `priority`, `description`                                 |
| `:Risk`     | `id`, `project`                | `title`, `topic`, `severity`, `status`, `mitigation`                         |
| `:Question` | `id`, `project`                | `title`, `status`, `answer`                                                  |
| `:Idea`     | `id`, `project`                | `title`, `topic`, `status`, `notes`                                          |
| `:CodeRef`  | `project`, `targetType`, `key` | —                                                                            |

All nodes also have `createdAt`, `updatedAt`.

**Controlled values:**
- Decision/ADR `status`: `proposed`|`accepted`|`rejected`|`superseded` (ADR also `draft`)
- Rule `severity`: `hard`|`soft`|`recommendation`
- Finding `type`: `bug`|`perf`|`constraint`|`security`
- Finding `status`: `open`|`resolved`|`obsolete`
- Task `status`: `todo`|`doing`|`done`|`blocked`|`cancelled`
- Risk `severity`: `low`|`medium`|`high`|`critical`; `status`: `open`|`mitigated`|`accepted`|`obsolete`
- Question `status`: `open`|`answered`|`obsolete`
- Idea `status`: `proposed`|`accepted`|`rejected`|`obsolete`

**ID format:** `DEC-`, `ADR-<n>-`, `RULE-`, `FIND-`, `TASK-`, `RISK-`, `CTX-`, `Q-`, `IDEA-` + `<topic>-<name>`

**Links:**
```
(:Project)-[:HAS_MEMORY]->(:Memory)-[:HAS_*]->(:Decision|:ADR|:Rule|:Context|:Finding|:Task|:Risk|:Question|:Idea)
(:Decision|...)-[:REFERS_TO]->(:CodeRef)-[:RESOLVES_TO]->(:Code|:Package|:File|:Class|:Interface|:Annotation|:Method|:Field)
```
`CodeRef.key`: project name → `Code`, package → `Package`, path → `File`, FQN → types/fields, signature → `Method`.

---

### Queries

#### Orientation (run at task start)

Run all six in **one bash block**. If `mgq` is not on `PATH`, define it once at the top of the first bash block (see Memgraph query tool section). Empty output = 0 rows (normal).

```bash
mgq() { mgconsole --host ${MG_HOST:-127.0.0.1} --port ${MG_PORT:-7687} ${MG_USER:+--username $MG_USER} ${MG_PASS:+--password $MG_PASS} --output-format=csv "$@"; }
echo "MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_RULE]->(r:Rule) RETURN r.id, r.severity, r.description ORDER BY r.severity;" | mgq
echo "MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_FINDING]->(f:Finding) WHERE f.status = 'open' RETURN f.id, f.type, f.summary;" | mgq
echo "MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_CONTEXT]->(c:Context) RETURN c.id, c.content, c.source;" | mgq
echo "MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_TASK]->(t:Task) WHERE t.status IN ['todo','doing','blocked'] RETURN t.id, t.title, t.status, t.priority ORDER BY t.priority;" | mgq
echo "MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_QUESTION]->(q:Question) WHERE q.status = 'open' RETURN q.id, q.title;" | mgq
echo "MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_RISK]->(r:Risk) WHERE r.status = 'open' RETURN r.id, r.title, r.severity;" | mgq
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

**Always include `m.startLine, m.endLine` when fetching methods** — use these with `view_range: [startLine, endLine]` to read only the relevant lines from source files instead of loading the entire file.

```cypher
// Methods / fields of a class — include line numbers for targeted view_range reads
MATCH (c:Class {fqn: '...', project: '{{PROJECT_NAME}}'})-[:DECLARES]->(m:Method)
RETURN m.signature, m.visibility, m.returnType, m.startLine, m.endLine ORDER BY m.name;

// Callers of a method
MATCH (caller:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
WHERE callee.signature CONTAINS 'MyClass.myMethod(' RETURN caller.signature;

// Cross-class dependencies
MATCH (caller:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
WITH split(split(caller.signature,'(')[0],'.') AS cp, split(split(callee.signature,'(')[0],'.') AS tp
WITH cp[size(cp)-2] AS cc, tp[size(tp)-2] AS tc WHERE cc <> tc
RETURN cc+' -> '+tc AS edge, COUNT(*) AS cnt ORDER BY cnt DESC;
```

Signature format: `pkg.ClassName.methodName(fully.qualified.ParamType, ...)`. Constructors: `<init>`.

---

### Task lifecycle

```cypher
MATCH (t:Task {id: 'TASK-<id>', project: '{{PROJECT_NAME}}'}) SET t.status = 'doing', t.updatedAt = datetime();
// on complete:
MATCH (t:Task {id: 'TASK-<id>', project: '{{PROJECT_NAME}}'}) SET t.status = 'done', t.updatedAt = datetime();
```

---

### Saving memory (mandatory before task close)

| Trigger                              | Node                            |
|--------------------------------------|---------------------------------|
| Design/implementation choice         | `:Decision` (`accepted`)        |
| Architectural direction              | `:ADR`                          |
| Future rule/constraint               | `:Rule`                         |
| Bug / perf issue / wrong assumption  | `:Finding` (`bug`/`perf`)       |
| Codebase limitation                  | `:Finding` (`constraint`)       |
| Files/schema/config/Cypher modified  | `:Context`                      |
| Unfinished work / follow-up          | `:Task` (`todo`)                |
| Open question                        | `:Question` (`open`)            |
| New/discovered risk                  | `:Risk` (`open`)                |

```cypher
// Create (adapt label/id/HAS_* for other types)
MERGE (m:Memory {project: '{{PROJECT_NAME}}'})
MERGE (d:Decision {id: 'DEC-<topic>-<name>', project: '{{PROJECT_NAME}}'})
SET d.title='<title>', d.topic='<topic>', d.status='accepted', d.rationale='<rationale>',
    d.createdAt=coalesce(d.createdAt, datetime()), d.updatedAt=datetime()
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
WHERE n.updatedAt >= datetime()-duration('PT5M')
RETURN type(r) AS rel, n.id AS id, labels(n) AS type;
```

---

### Staleness

```cypher
MATCH (c:Code {project: '{{PROJECT_NAME}}'}) RETURN c.lastIngested;
```

> `:Code` is the intermediary node between `:Project` and `:Package`/`:File` — it is **not** listed in the schema table above but exists in the graph. `lastIngested` is a Unix-epoch **microseconds** integer (e.g. `1778314313032240`).

> Memory is not logs. Store only what improves future decisions.
