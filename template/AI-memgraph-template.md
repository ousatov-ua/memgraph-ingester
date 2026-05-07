## Knowledge graph (Memgraph)

Repo indexed under **`{{PROJECT_NAME}}`**. Use MCP or `mgconsole` for queries.
All queries MUST include `project: '{{PROJECT_NAME}}'`.

### Lookup order (MANDATORY)

1. **Memgraph** — structure, relationships, memory, metadata
2. **Source files** — line-level implementation detail only
3. **grep/glob** — string literals, comments, non-Java resources only
4. **Other tools** — last resort

Always query Memgraph first for: classes, interfaces, methods, fields, hierarchies, call chains,
annotations, cross-package dependencies, architecture, and memory nodes.

**BLOCKING — before any class/interface work:** query full hierarchy (ancestors + descendants).  
**BLOCKING — before any task:** query `:Rule`, `:Finding`, `:Context` nodes.  
**BLOCKING — before closing task:** save all findings/decisions as Memory nodes and verify.

When Memgraph returns no results, fall back to text search and state why.

---

### Schema

#### Code nodes

| Label         | Key                    | Notable properties                                                                               |
|---------------|------------------------|--------------------------------------------------------------------------------------------------|
| `:Project`    | `name`                 | —                                                                                                |
| `:Code`       | `project`              | `sourceRoots`, `lastIngested`                                                                    |
| `:Package`    | `(name, project)`      | —                                                                                                |
| `:File`       | `(path, project)`      | `lastModified` (epoch ms)                                                                        |
| `:Class`      | `(fqn, project)`       | `name`, `packageName`, `isAbstract`, `visibility`, `isEnum`, `isRecord`, `isFinal`, `isExternal` |
| `:Interface`  | `(fqn, project)`       | `name`, `packageName`, `visibility`, `isFinal`, `isExternal`                                     |
| `:Annotation` | `(fqn, project)`       | `name`, `packageName`, `visibility`, `isExternal`                                                |
| `:Method`     | `(signature, project)` | `name`, `returnType`, `visibility`, `isStatic`, `startLine`, `endLine`, `isSynthetic`            |
| `:Field`      | `(fqn, project)`       | `name`, `type`, `visibility`, `isStatic`                                                         |

#### Relationships

```cypher

(:Project)- [:CONTAINS] - >(:Code)
(:Code)- [:CONTAINS] - >(:Package|:File)
(:Package)- [:CONTAINS] - >(:Class|:Interface|:Annotation)
(:File)- [:DEFINES] - >(:Class|:Interface|:Annotation)
(:Class)- [:EXTENDS] - >(:Class)
(:Class)- [:IMPLEMENTS] - >(:Interface)
(:Interface)- [:EXTENDS] - >(:Interface)
(:Class|:Interface)- [:DECLARES] - >(:Method|:Field)
(:Method)- [:CALLS] - >(:Method)
(:*)- [:ANNOTATED_WITH] - >(:Annotation)
```

#### Caveats

- **`CALLS` has no `project` property** — filter both ends:
  `(a:Method {project:'...'})-[:CALLS]->(b:Method {project:'...'})`.
- **`CALLS`/`ANNOTATED_WITH`** — best-effort, within-project only; missing edges ≠ no relationship.
- **Without `--classpath`**: cross-library calls unresolved; external type names appear as simple
  names.
- **CALLS gaps**: complex type inference may omit edges; name-based fallback used when exactly one
  method match exists.
- **External nodes**: `isExternal = true`; use `WHERE NOT n.isExternal` to exclude.
- **Annotation FQN**: non-JDK annotations stored with simple name (e.g. `fqn: 'Test'`); JDK only use
  full FQN.
- **Constructors**: `:Method` with `name = '<init>'`.
- **Synthetic members**: `isSynthetic = true`; use `WHERE NOT m.isSynthetic` to exclude.
- **Nested classes**: FQN uses `$` — `com.example.Outer$Inner`.
- **`DECLARES`**: always add label filter — `-[:DECLARES]->(m:Method)`.
- **`visibility`**: `"public"`, `"protected"`, `"private"`, `""` (package-private).
- **EXTENDS/IMPLEMENTS**: unresolvable external parents may use simple name.

---

### Memory schema

**Strict — no extra properties allowed beyond what's listed.**

#### Nodes

| Label       | Key props                      | All allowed properties (identity + data)                                                                      |
|-------------|--------------------------------|---------------------------------------------------------------------------------------------------------------|
| `:Memory`   | `project`                      | `project`                                                                                                     |
| `:Decision` | `id`, `project`                | `id`, `project`, `title`, `topic`, `status`, `rationale`, `consequences`, `createdAt`, `updatedAt`            |
| `:ADR`      | `id`, `project`                | `id`, `project`, `number`, `title`, `status`, `context`, `decision`, `consequences`, `createdAt`, `updatedAt` |
| `:Rule`     | `id`, `project`                | `id`, `project`, `title`, `topic`, `severity`, `description`, `createdAt`, `updatedAt`                        |
| `:Context`  | `id`, `project`                | `id`, `project`, `title`, `topic`, `content`, `source`, `createdAt`, `updatedAt`                              |
| `:Finding`  | `id`, `project`                | `id`, `project`, `title`, `topic`, `type`, `summary`, `evidence`, `createdAt`, `updatedAt`                    |
| `:Task`     | `id`, `project`                | `id`, `project`, `title`, `status`, `priority`, `description`, `createdAt`, `updatedAt`                       |
| `:Risk`     | `id`, `project`                | `id`, `project`, `title`, `topic`, `severity`, `status`, `mitigation`, `createdAt`, `updatedAt`               |
| `:Question` | `id`, `project`                | `id`, `project`, `title`, `status`, `answer`, `createdAt`, `updatedAt`                                        |
| `:Idea`     | `id`, `project`                | `id`, `project`, `title`, `topic`, `status`, `notes`, `createdAt`, `updatedAt`                                |
| `:CodeRef`  | `project`, `targetType`, `key` | `project`, `targetType`, `key`                                                                                |

#### Controlled values

- Decision/ADR `status`: `proposed`|`accepted`|`rejected`|`superseded` (ADR also `draft`)
- Rule `severity`: `hard`|`soft`|`recommendation`
- Finding `type`: `bug`|`perf`|`constraint`|`security`
- Task `status`: `todo`|`doing`|`done`|`blocked`|`cancelled`
- Risk `severity`: `low`|`medium`|`high`|`critical`; `status`: `open`|`mitigated`|`accepted`|
  `obsolete`
- Question `status`: `open`|`answered`|`obsolete`

#### ID format

`DEC-`, `ADR-<n>-`, `RULE-`, `FIND-`, `TASK-`, `RISK-`, `CTX-`, `Q-`, `IDEA-` + `<topic>-<name>`

---

#### Links

```cypher

(:Project)- [:HAS_MEMORY] - >(:Memory)- [:HAS_ * ] - >(:Decision|:ADR|:Rule|:Context|:Finding|:Task|:Risk|:Question|:Idea)
(:Decision|:ADR|:Rule|:Context|:Finding|:Task|:Risk|:Idea)- [:REFERS_TO] - >(:CodeRef)- [:RESOLVES_TO] - >(:Code|:Package|:File|:Class|:Interface|:Annotation|:Method|:Field)
```

`CodeRef.key`: project name for `Code`, package for `Package`, path for `File`, FQN for
types/fields, signature for `Method`.

---

### Queries

#### Orientation

```cypher

MATCH (n {project: '{{PROJECT_NAME}}'})
RETURN n
  LIMIT 50;
```

#### Hierarchy (run before touching any class/interface)

```cypher
// Direct parents & children
MATCH (c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})
OPTIONAL MATCH (c)-[:EXTENDS]->(parent:Class)
OPTIONAL MATCH (c)-[:IMPLEMENTS]->(iface:Interface)
OPTIONAL MATCH (child:Class)-[:EXTENDS]->(c)
RETURN c.fqn AS self, collect(DISTINCT parent.fqn) AS superclasses,
       collect(DISTINCT iface.fqn) AS interfaces, collect(DISTINCT child.fqn) AS subclasses;

// Full ancestor chain
MATCH path = (c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})
  -[:EXTENDS*]->(ancestor:Class {project: '{{PROJECT_NAME}}'})
RETURN [n IN nodes(path) | n.fqn] AS ancestorChain;

// Full descendant chain
MATCH path = (descendant:Class {project: '{{PROJECT_NAME}}'})
  -[:EXTENDS*]->(c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})
RETURN [n IN nodes(path) | n.fqn] AS descendantChain;

// All interfaces (direct + transitive through superclasses)
MATCH (c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})
        -[:EXTENDS*0..]->(anc:Class {project: '{{PROJECT_NAME}}'})-[:IMPLEMENTS]->(i:Interface)
RETURN DISTINCT i.fqn AS implementedInterface;

// Interface hierarchy
MATCH path = (i:Interface {fqn: 'com.example.MyInterface', project: '{{PROJECT_NAME}}'})
  -[:EXTENDS*]->(superIface:Interface {project: '{{PROJECT_NAME}}'})
RETURN [n IN nodes(path) | n.fqn] AS interfaceChain;

// All implementors of an interface
MATCH (c:Class {project: '{{PROJECT_NAME}}'})
        -[:IMPLEMENTS]->(:Interface {fqn: 'com.example.MyInterface', project: '{{PROJECT_NAME}}'})
RETURN c.fqn AS implementor
UNION
MATCH (sub:Class {project: '{{PROJECT_NAME}}'})-[:EXTENDS*]->(c:Class {project: '{{PROJECT_NAME}}'})
        -[:IMPLEMENTS]->(:Interface {fqn: 'com.example.MyInterface', project: '{{PROJECT_NAME}}'})
RETURN sub.fqn AS implementor;
```

#### Code search

```cypher
// Methods of a class
MATCH (c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})-[:DECLARES]->(m:Method)
RETURN m.signature, m.visibility, m.returnType
  ORDER BY m.name;

// Callers of a method
MATCH (caller:Method {project: '{{PROJECT_NAME}}'})
        -[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
  WHERE callee.signature CONTAINS 'MyClass.myMethod('
RETURN caller.signature;

// Cross-class dependencies
MATCH (caller:Method {project: '{{PROJECT_NAME}}'})
        -[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
WITH split(split(caller.signature, '(')[0], '.') AS cParts,
     split(split(callee.signature, '(')[0], '.') AS tParts
WITH cParts[size(cParts) - 2] AS callerClass, tParts[size(tParts) - 2] AS calleeClass
  WHERE callerClass <> calleeClass
RETURN callerClass + ' -> ' + calleeClass AS edge, COUNT(*) AS cnt
  ORDER BY cnt DESC;

// Annotations on a class
MATCH (c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})
        -[:ANNOTATED_WITH]->(a:Annotation)
RETURN a.fqn;
```

Method signature format: `package.ClassName.methodName(fully.qualified.ParamType, ...)`.
Constructors use `<init>`.

#### Orientation queries (run at task start)

```cypher
// Rules — follow before any code change
MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_RULE]->(r:Rule)
RETURN r.id, r.severity, r.description
  ORDER BY r.severity;

// Findings — known bugs, limitations, performance issues
MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_FINDING]->(f:Finding)
RETURN f.id, f.type, f.summary;

// Context — why things work the way they do
MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_CONTEXT]->(c:Context)
RETURN c.id, c.content, c.source;
```

Follow accepted Decisions/ADRs; never violate hard Rules; surface Risks before changes; don't revive
rejected Ideas.

---

### Saving memory (mandatory before task close)

**Create a node for every matching trigger:**

| Trigger                             | Node                             |
|-------------------------------------|----------------------------------|
| Design/implementation choice        | `:Decision` (`status: accepted`) |
| Architectural direction             | `:ADR`                           |
| Future rule/constraint              | `:Rule`                          |
| Bug, perf issue, wrong assumption   | `:Finding` (`type: bug`/`perf`)  |
| Codebase limitation                 | `:Finding` (`type: constraint`)  |
| Files/schema/config/Cypher modified | `:Context`                       |
| Unfinished work / follow-up         | `:Task` (`status: todo`)         |
| Open question                       | `:Question` (`status: open`)     |
| New or discovered risk              | `:Risk` (`status: open`)         |

```cypher
// Create Decision (adapt node type/label/ID/HAS_* for others)
MERGE (m:Memory {project: '{{PROJECT_NAME}}'})
MERGE (d:Decision {id: 'DEC-<topic>-<name>', project: '{{PROJECT_NAME}}'})
SET d.title = '<title>', d.topic = '<topic>', d.status = 'accepted',
d.rationale = '<rationale>', d.createdAt = coalesce(d.createdAt, datetime()), d.
  updatedAt = datetime()
MERGE (m)-[:HAS_DECISION]->(d)
RETURN d.id;

// Link to code
MERGE (ref:CodeRef {project: '{{PROJECT_NAME}}', targetType: 'Class', key: '<fqn>'})
MERGE (d)-[:REFERS_TO]->(ref)
MERGE (ref)-[:RESOLVES_TO]->(c:Class {fqn: '<fqn>', project: '{{PROJECT_NAME}}'})
RETURN ref;

// Verify — must return rows; retry if empty
MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[r]->(n)
  WHERE n.updatedAt >= datetime() - duration('PT5M')
RETURN type(r) AS rel, n.id AS id, labels(n) AS type;
```

---

### Staleness

```cypher

MATCH (c:Code {project: '{{PROJECT_NAME}}'})
RETURN c.lastIngested;
```

> Memory is not logs. Store only what improves future decisions.
