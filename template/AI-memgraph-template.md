## Knowledge graph (Memgraph)

This repo is indexed in Memgraph under the project name **`{{PROJECT_NAME}}`**.

In case if Memgraph MCP is not installed, use `mgconsole` for all queries.

### Search priority (MANDATORY)

**Memgraph is the primary knowledge source for this project. Always query it first.**

Use the following lookup order for every code-related question:

1. **Memgraph graph query** — query the knowledge graph for structure, relationships, memory,
   and metadata. This is the fastest and most accurate source for anything the graph covers.
2. **Source file reads** (`view`/`cat`) — read specific files only when you need line-level detail
   (implementations, logic, exact syntax) that the graph does not store.
3. **Text search** (`grep`/`ripgrep`/`find`/glob) — use only when:
    - The information is absent from the graph (e.g., string literals, comments, config files,
      non-Java resources).
    - A Memgraph query returned no results or incomplete results for the question.
    - You need to search non-indexed files (XML, YAML, properties, scripts, etc.).
4. **Other tools** (Python scripts, `jq`, etc.) — last resort for data the above cannot reach.

**Do NOT skip to step 2, 3, or 4 without first attempting a Memgraph query when the question
involves any of the following:**

- Class, interface, annotation, method, or field existence, location, or properties
- Inheritance hierarchies (`EXTENDS`, `IMPLEMENTS`)
- Call chains and callers/callees (`CALLS`)
- Package structure and file-to-type mappings
- Annotations on any element
- Cross-class or cross-package dependencies
- Any architectural or design question about the codebase
- Prior decisions, rules, findings, risks, or context (`:Memory` subgraph)

**When a Memgraph query returns partial or no results**, you may then supplement with text search.
Always state explicitly that Memgraph was queried first and why the fallback was needed.

### Trigger phrases you can use

- "check the structure of X" → query all methods/fields of class X
- "who calls X" → find callers via `CALLS` edges
- "show dependencies of X" → cross-class call graph
- "check hierarchy" → `EXTENDS`/`IMPLEMENTS` chains
- "explore the graph" → broad `MATCH (n {project:...})` query
- "read memory" → query all `:Memory` nodes before a decision

### Default behavior

When working on any Java task, always run at least one Memgraph query to orient before reading
source files. This applies to understanding, modifying, refactoring, debugging, and extending code.
Graph queries replace `grep` for all structure and relationship questions — use `grep` only for
content the graph does not index (string literals, comments, resource files).

### Anchors

```cypher

(:Project {name})- [:CONTAINS] - >(:Code {project})
(:Project {name})- [:HAS_MEMORY] - >(:Memory {project})
```

All queries MUST include `project: '{{PROJECT_NAME}}'`.

---

### Code (read first)

#### Nodes

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

- **Best-effort edges** — `CALLS` and `ANNOTATED_WITH` are within-project only and only for
  resolvable symbols. Missing edges don't mean no relationship exists. Use `--classpath` with
  dependency JARs to improve resolution coverage.
- **`CALLS` has no `project` property** — filter via node properties on both ends:
  `(caller:Method {project: '...'})-[:CALLS]->(callee:Method {project: '...'})`.
- **`--classpath` impact** — without `--classpath`, cross-class calls whose parameter types come
  from external libraries (e.g. JavaParser, Neo4j driver) won't resolve and produce no `CALLS`
  edge. Same-class calls are unaffected. Method signatures may also show simple names instead of
  FQNs for external types (e.g. `Session` instead of `org.neo4j.driver.Session`).
- **CALLS gaps** — call sites where arguments involve complex type inference (e.g. `Map.of()` with
  mixed types) or where parameter types are project-internal classes may not resolve; those edges
  will be absent even after two ingestion passes. For unresolved calls (same-class, scoped
  cross-class, method references, and constructor references), a name-based fallback creates the
  edge when exactly one method with that name exists in the target type. Constructor calls
  (`new X(...)`) and constructor delegation (`this(...)` / `super(...)`) are also tracked. Nested
  class constructor calls may not resolve because the resolver uses dot-separated FQNs while the
  graph stores `$`-separated FQNs.
- **EXTENDS/IMPLEMENTS resolution** — when the symbol solver cannot resolve an external parent type,
  the FQN is inferred from import statements or falls back to the source-level name. Unresolvable
  types may appear with a simple name rather than a full FQN.
- **External / phantom nodes** — when a class extends or implements an external type, the parent
  node is created with `isExternal = true` and its `name`/`packageName` inferred from the FQN.
  External annotations are also marked `isExternal = true`. Project-internal nodes always have
  `isExternal = false`. Use `WHERE NOT n.isExternal` to exclude external types from queries.
- **Annotation FQN** — external library annotations (JUnit 5, Spring, picocli, etc.) are stored with
  their **simple name** as `fqn` because the symbol resolver cannot reach them (e.g. `fqn: "Test"`,
  `fqn: "Command"`). Only JDK annotations resolve to a full FQN (e.g. `fqn: "java.lang.Override"`).
  Always query by simple name for non-JDK annotations:
  `MATCH (a:Annotation {fqn: 'Test', project: '...'})`.
- **Constructors** — stored as `:Method` with `name = '<init>'`.
- **Synthetic members** — record canonical constructors and accessor methods are synthesized with
  `isSynthetic = true` when not explicitly declared in source. Use
  `WHERE NOT m.isSynthetic` to filter them out.
- **Nested classes** — FQN uses `$`: `com.example.Outer$Inner`.
- **`DECLARES`** — covers both methods and fields; always add a label filter:
  `-[:DECLARES]->(m:Method)`.
- **`visibility`** — lowercase Java keywords: `"public"`, `"protected"`, `"private"`, `""` (
  package-private).

---

### Memory (read before decisions)

**Use only mentioned properties**

#### Nodes

| Label       | Key                          | Notable properties             |
|-------------|------------------------------|--------------------------------|
| `:Memory`   | `project`                    | root                           |
| `:Decision` | `(id, project)`              | `title`, `status`, `rationale` |
| `:ADR`      | `(id, project)`              | `number`, `status`, `decision` |
| `:Rule`     | `(id, project)`              | `severity`, `description`      |
| `:Context`  | `(id, project)`              | `content`, `source`            |
| `:Finding`  | `(id, project)`              | `type`, `summary`              |
| `:Task`     | `(id, project)`              | `status`, `priority`           |
| `:Risk`     | `(id, project)`              | `severity`, `status`           |
| `:Question` | `(id, project)`              | `status`, `answer`             |
| `:Idea`     | `(id, project)`              | `status`, `notes`              |
| `:CodeRef`  | `(project, targetType, key)` | stable code reference          |

#### Controlled values

- Decision: proposed | accepted | rejected | superseded
- ADR: draft | accepted | rejected | superseded
- Rule: hard | soft | recommendation
- Task: todo | doing | done | blocked | cancelled
- Risk: open | mitigated | accepted | obsolete

#### ID format

```
DEC-<topic>-<name>
ADR-<n>-<name>
RULE-<topic>-<name>
FIND-<topic>-<name>
TASK-<topic>-<name>
RISK-<topic>-<name>
CTX-<topic>-<name>
Q-<topic>-<name>
IDEA-<topic>-<name>
```

---

### Links

```cypher

(:Project)- [:HAS_MEMORY] - >(:Memory)
(:Memory)- [:HAS_ * ] - >(:Decision|:ADR|:Rule|:Context|:Finding|:Task|:Risk|:Question|:Idea)

(:Decision|:ADR|:Rule|:Context|:Finding|:Task|:Risk|:Idea)- [:REFERS_TO] - >(:CodeRef)
(:CodeRef)- [:RESOLVES_TO] - >(:Code|:Package|:File|:Class|:Interface|:Annotation|:Method|:Field)
```

`CodeRef.targetType` is one of `Code`, `Package`, `File`, `Class`, `Interface`, `Annotation`,
`Method`, or `Field`. `CodeRef.key` uses the matching code identity: project name for `Code`,
package name for `Package`, path for `File`, FQN for types/annotations/fields, and signature for
`Method`.

---

### Usage

#### Always query first

```cypher

MATCH (n {project: '{{PROJECT_NAME}}'})
RETURN n
  LIMIT 50;
```

#### Searching code structure

**Find all methods of a class:**

```cypher

MATCH (c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})
        -[:DECLARES]->(m:Method)
RETURN m.signature, m.visibility, m.returnType
  ORDER BY m.name;
```

**Find who calls a method:**

```cypher

MATCH (caller:Method {project: '{{PROJECT_NAME}}'})
        -[:CALLS]->
      (callee:Method {project: '{{PROJECT_NAME}}'})
  WHERE callee.signature CONTAINS 'MyClass.myMethod('
RETURN caller.signature;
```

**Find cross-class dependencies (which classes call which):**

```cypher

MATCH (caller:Method {project: '{{PROJECT_NAME}}'})
        -[:CALLS]->
      (callee:Method {project: '{{PROJECT_NAME}}'})
WITH split(split(caller.signature, '(')[0], '.') AS cParts,
     split(split(callee.signature, '(')[0], '.') AS tParts
WITH cParts[size(cParts) - 2] AS callerClass,
     tParts[size(tParts) - 2] AS calleeClass
  WHERE callerClass <> calleeClass
RETURN callerClass + ' -> ' + calleeClass AS edge, COUNT(*) AS cnt
  ORDER BY cnt DESC;
```

**Find class hierarchy:**

```cypher

MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:EXTENDS]->(p:Class)
RETURN c.fqn AS child, p.fqn AS parent;

MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:IMPLEMENTS]->(i:Interface)
RETURN c.fqn AS class, i.fqn AS interface;
```

**Find annotations on a class or method:**

```cypher

MATCH (c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})
        -[:ANNOTATED_WITH]->(a:Annotation)
RETURN a.fqn;
```

**Method signature format** — signatures follow the pattern
`package.ClassName.methodName(fully.qualified.ParamType, ...)`. Constructors use `<init>` as the
method name. Generic types are included (e.g. `java.util.List<java.lang.String>`). Without
`--classpath`, external library types appear as simple names (e.g. `Session` instead of
`org.neo4j.driver.Session`).

#### Decision rules

- Follow accepted Decision / ADR
- Do not violate hard Rule
- Surface Risk before change
- Do not revive rejected Idea

#### When to create memory

**Before completing a task**, always check whether any of the following occurred during the work and
create the corresponding Memory nodes (Finding, Decision, Rule, Context, Risk, etc.):

- new decision or design choice (→ `:Decision`)
- discovered constraint or limitation (→ `:Finding` with `type: 'constraint'`)
- bug or performance finding (→ `:Finding` with `type: 'bug'` or `'perf'`)
- non-trivial context that future sessions need (→ `:Context`)
- follow-up work or open questions (→ `:Task` or `:Question`)
- identified risk (→ `:Risk`)

Do **not** skip this step. Memory nodes are the only way context survives across sessions. Link each
memory to relevant code via `:CodeRef` → `:RESOLVES_TO` edges.

#### Create template

```cypher

MERGE (m:Memory {project: '{{PROJECT_NAME}}'})
MERGE (d:Decision {id: $id, project: '{{PROJECT_NAME}}'})
SET d.title = $title, d.status = 'accepted', d.createdAt = datetime(), d.updatedAt = datetime()
MERGE (m)-[:HAS_DECISION]->(d)
```

#### Create code reference

```cypher

MERGE (ref:CodeRef {project: '{{PROJECT_NAME}}', targetType: 'Class', key: $fqn})
MERGE (d)-[:REFERS_TO]->(ref)
```

---

### Principle

Memory is not logs. Store only what improves future decisions.

### Staleness

```cypher

MATCH (c:Code {project: '{{PROJECT_NAME}}'})
RETURN c.lastIngested;
```
