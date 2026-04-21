## Code knowledge graph (Memgraph MCP)

This repo is indexed in Memgraph under the project name **`{{PROJECT_NAME}}`**.

**Before any structural task — call graphs, inheritance, implementations, annotation usage — run at
least one Memgraph query first. Do not rely solely on filesystem search.**

### Scoping

Every query must include `project: '{{PROJECT_NAME}}'`. The instance hosts multiple projects.

```cypher
// direct property filter (simple lookups)
MATCH (c:Class {project: '{{PROJECT_NAME}}'}) ...

// anchor traversal (exploration)
MATCH (:Project {name: '{{PROJECT_NAME}}'})-[:CONTAINS*]->(x) ...
```

### Schema

**Nodes**

| Label         | Key                    | Notable properties                                                      |
|---------------|------------------------|-------------------------------------------------------------------------|
| `:Project`    | `name`                 | `sourceRoots`, `lastIngested`                                           |
| `:Package`    | `(name, project)`      | —                                                                       |
| `:File`       | `(path, project)`      | `lastModified` (epoch ms)                                               |
| `:Class`      | `(fqn, project)`       | `name`, `packageName`, `isAbstract`, `visibility`, `isEnum`, `isRecord` |
| `:Interface`  | `(fqn, project)`       | `name`, `packageName`, `visibility`                                     |
| `:Annotation` | `(fqn, project)`       | `name`, `packageName`, `visibility`                                     |
| `:Method`     | `(signature, project)` | `name`, `returnType`, `visibility`, `isStatic`, `startLine`, `endLine`  |
| `:Field`      | `(fqn, project)`       | `name`, `type`, `visibility`, `isStatic`                                |

**Relationships**

```
(:Project)                                     -[:CONTAINS]->      (:Package | :File)
(:Package)                                     -[:CONTAINS]->      (:Class | :Interface | :Annotation)
(:File)                                        -[:DEFINES]->       (:Class | :Interface | :Annotation)
(:Class)                                       -[:EXTENDS]->       (:Class)
(:Class)                                       -[:IMPLEMENTS]->    (:Interface)
(:Interface)                                   -[:EXTENDS]->       (:Interface)
(:Class | :Interface)                          -[:DECLARES]->      (:Method | :Field)
(:Method)                                      -[:CALLS]->         (:Method)
(:Class | :Interface | :Annotation | :Method | :Field) -[:ANNOTATED_WITH]-> (:Annotation)
```

### Caveats

- **Best-effort edges** — `CALLS` and `ANNOTATED_WITH` are within-project only and only for
  resolvable symbols. Missing edges don't mean no relationship exists.
- **CALLS gaps** — call sites where arguments involve complex type inference (e.g. `Map.of()` with
  mixed types) or where parameter types are project-internal classes may not resolve; those edges
  will be absent even after two ingestion passes.
- **Annotation FQN** — external library annotations (JUnit 5, Spring, picocli, etc.) are stored with
  their **simple name** as `fqn` because the symbol resolver cannot reach them (e.g. `fqn: "Test"`,
  `fqn: "Command"`). Only JDK annotations resolve to a full FQN (e.g. `fqn: "java.lang.Override"`).
  Always query by simple name for non-JDK annotations:
  `MATCH (a:Annotation {fqn: 'Test', project: '...'})`.
- **Constructors** — stored as `:Method` with `name = '<init>'`.
- **Nested classes** — FQN uses `$`: `com.example.Outer$Inner`.
- **`DECLARES`** — covers both methods and fields; always add a label filter:
  `-[:DECLARES]->(m:Method)`.
- **`visibility`** — lowercase Java keywords: `"public"`, `"protected"`, `"private"`, `""` (
  package-private).

### Graph vs. filesystem

Use the graph for **structure**: call chains, inheritance, implementations, annotation usage, class
locations.  
Use filesystem tools for **content**: method bodies, Javadoc, string literals, non-Java files (
`pom.xml`, YAML, SQL).

### Staleness

```cypher

MATCH (p:Project {name: '{{PROJECT_NAME}}'})
RETURN p.lastIngested;
```

Re-ingestion is a separate tool — ask to run it rather than triggering it from Claude Code.
