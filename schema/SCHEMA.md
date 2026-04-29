# Memgraph Graph Model Reference

Documentation for the Java codebase and Memory knowledge graph. The DDL lives in
`src/main/resources/io/github/ousatov/tools/memgraph/cypher/create-schema.cypher` — this file
describes the model.

## Anchor nodes

`(:Project)` is the project anchor. Its identity is the `name` property. Code-specific graph data
hangs below `(:Project)-[:CONTAINS]->(:Code)`. `:Code` and every code node carry a `project`
property matching the project name, so clients can filter either via anchor traversal or direct
property matches.

Memory data hangs below `(:Project)-[:HAS_MEMORY]->(:Memory)`. `:Memory` and every memory item
carry the same `project` property. Code nodes are produced by the ingester; memory nodes are
intended for durable agent/client-authored decisions, context, and follow-up work.

## Code nodes

| Label | Key | Other properties |
|---|---|---|
| `:Project` | `name` | — |
| `:Code` | `project` | `sourceRoots` (string array), `lastIngested` |
| `:Package` | `name`, `project` | — |
| `:File` | `path`, `project` | `lastModified` (epoch millis) |
| `:Class` | `fqn`, `project` | `name`, `packageName`, `isAbstract`, `visibility`, `isEnum`, `isRecord` |
| `:Interface` | `fqn`, `project` | `name`, `packageName`, `visibility` |
| `:Annotation` | `fqn`, `project` | `name`, `packageName`, `visibility` |
| `:Method` | `signature`, `project` | `name`, `returnType`, `visibility`, `isStatic`, `startLine`, `endLine` |
| `:Field` | `fqn`, `project` | `name`, `type`, `visibility`, `isStatic` |

## Memory nodes

All memory item labels are unique by `(id, project)`. Most memory items also use `title`, `topic`,
`createdAt`, and `updatedAt` when useful.

| Label | Key | Other properties |
|---|---|---|
| `:Memory` | `project` | — |
| `:Decision` | `id`, `project` | `title`, `topic`, `status`, `rationale`, `consequences`, `createdAt`, `updatedAt` |
| `:ADR` | `id`, `project` | `number`, `title`, `status`, `context`, `decision`, `consequences`, `createdAt`, `updatedAt` |
| `:Rule` | `id`, `project` | `title`, `topic`, `severity`, `description`, `createdAt`, `updatedAt` |
| `:Context` | `id`, `project` | `title`, `topic`, `content`, `source`, `createdAt`, `updatedAt` |
| `:Finding` | `id`, `project` | `title`, `topic`, `type`, `summary`, `evidence`, `createdAt`, `updatedAt` |
| `:Task` | `id`, `project` | `title`, `status`, `priority`, `description`, `createdAt`, `updatedAt` |
| `:Risk` | `id`, `project` | `title`, `topic`, `severity`, `status`, `mitigation`, `createdAt`, `updatedAt` |
| `:Question` | `id`, `project` | `title`, `status`, `answer`, `createdAt`, `updatedAt` |
| `:Idea` | `id`, `project` | `title`, `topic`, `status`, `notes`, `createdAt`, `updatedAt` |

## Code relationships

```
(Project)   -[:CONTAINS]->      (Code)
(Code)      -[:CONTAINS]->      (Package)
(Code)      -[:CONTAINS]->      (File)
(Package)   -[:CONTAINS]->      (Class | Interface | Annotation)
(File)      -[:DEFINES]->       (Class | Interface | Annotation)
(Class)     -[:EXTENDS]->       (Class)
(Interface) -[:EXTENDS]->       (Interface)
(Class)     -[:IMPLEMENTS]->    (Interface)
(Class | Interface) -[:DECLARES]-> (Method | Field)
(Method)    -[:CALLS]->         (Method)
(Class | Interface | Annotation | Method | Field) -[:ANNOTATED_WITH]-> (Annotation)
```

## Memory relationships

```
(Project) -[:HAS_MEMORY]->   (Memory)

(Memory)  -[:HAS_DECISION]-> (Decision)
(Memory)  -[:HAS_ADR]->      (ADR)
(Memory)  -[:HAS_RULE]->     (Rule)
(Memory)  -[:HAS_CONTEXT]->  (Context)
(Memory)  -[:HAS_FINDING]->  (Finding)
(Memory)  -[:HAS_TASK]->     (Task)
(Memory)  -[:HAS_RISK]->     (Risk)
(Memory)  -[:HAS_QUESTION]-> (Question)
(Memory)  -[:HAS_IDEA]->     (Idea)

(Decision | ADR) -[:APPLIES_TO]->  (Code | Package | File | Class | Interface | Annotation | Method | Field)
(Rule)           -[:GOVERNS]->     (Code | Package | File | Class | Interface | Annotation | Method | Field)
(Context)        -[:DESCRIBES]->   (Code | Package | File | Class | Interface | Annotation | Method | Field)
(Finding)        -[:OBSERVED_IN]-> (Code | Package | File | Class | Interface | Annotation | Method | Field)
(Task)           -[:CHANGES]->     (Code | Package | File | Class | Interface | Annotation | Method | Field)
(Risk)           -[:AFFECTS]->     (Code | Package | File | Class | Interface | Annotation | Method | Field)
(Idea)           -[:RELATES_TO]->  (Code | Package | File | Class | Interface | Annotation | Method | Field)
```

Common memory-to-memory links:

```
(Decision) -[:SUPERSEDES]->   (Decision)
(Decision) -[:MOTIVATED_BY]-> (Finding | Rule | Risk | Question)
(Decision) -[:REJECTS]->      (Idea)
(Decision) -[:IMPLEMENTS]->   (ADR)

(Idea)     -[:EVOLVED_INTO]-> (Decision | Task | ADR)
(Idea)     -[:BLOCKED_BY]->   (Rule | Risk | Question)

(Task)     -[:IMPLEMENTS]->   (Decision | ADR | Idea)
(Task)     -[:BLOCKED_BY]->   (Task | Risk | Question | Rule)
(Task)     -[:DEPENDS_ON]->   (Task | Decision | Context)

(Finding)  -[:SUPPORTS]->     (Decision | Rule | ADR)
(Finding)  -[:CONTRADICTS]->  (Decision | Context | Idea)

(Risk)     -[:MITIGATED_BY]-> (Decision | Task | Rule)
(Rule)     -[:DERIVED_FROM]-> (Decision | ADR | Finding)

(Question) -[:ANSWERED_BY]->  (Decision | Context | Finding | ADR)
(ADR)      -[:SUPERSEDES]->   (ADR)
```

## Notes on constraints

- No existence constraints are enforced. Earlier versions used them, but they caused failures when ingesting partial graphs or when external types were referenced without a `project`. The composite uniqueness constraints are sufficient — the ingester always sets `project`.
- `:Project`, `:Code`, and `:Memory` each use a single-property uniqueness constraint. Code and memory item nodes use composite `(key, project)` uniqueness.
- Nested/inner classes use `$` as separator in FQN (e.g. `com.example.Outer$Inner`).
- `CALLS` edges only connect methods within the same project. External library calls are dropped to avoid phantom nodes. A second wipe-less re-ingestion pass fills in any cross-file edges missed due to ingestion ordering.
- Memory relationships are conventional, not constrained by DDL. Agents should keep memory scoped with `project` and link memory back to the most concrete applicable code node.

## Memory controlled values

- Decision status: `proposed`, `accepted`, `rejected`, `superseded`
- ADR status: `draft`, `accepted`, `rejected`, `superseded`
- Rule severity: `hard`, `soft`, `recommendation`
- Task status: `todo`, `doing`, `done`, `blocked`, `cancelled`
- Question status: `open`, `answered`, `obsolete`
- Risk severity: `low`, `medium`, `high`, `critical`
- Risk status: `open`, `mitigated`, `accepted`, `obsolete`

## Common queries

List all projects:
```cypher
MATCH (p:Project)-[:CONTAINS]->(c:Code) RETURN p.name, c.sourceRoots, c.lastIngested;
```

All classes in a project:
```cypher
MATCH (:Project {name: 'olus-dev'})-[:CONTAINS]->(:Code)-[:CONTAINS]->(:File)-[:DEFINES]->(c:Class)
RETURN c.fqn;
```

Who calls a given method:
```cypher
MATCH (caller:Method)-[:CALLS]->(m:Method {name: 'save', project: 'olus-dev'})
RETURN caller.signature;
```

Public API of a class:
```cypher
MATCH (c:Class {fqn: 'com.example.Widget', project: 'olus-dev'})
      -[:DECLARES]->(m:Method {visibility: 'public'})
RETURN m.name, m.signature;
```

List accepted decisions for a project:
```cypher
MATCH (:Memory {project: 'olus-dev'})-[:HAS_DECISION]->(d:Decision {status: 'accepted'})
RETURN d.id, d.title, d.rationale
  ORDER BY d.updatedAt DESC;
```

Find memory linked to a file:
```cypher
MATCH (file:File {path: 'src/main/java/com/example/Widget.java', project: 'olus-dev'})
MATCH (memory {project: 'olus-dev'})-[rel]->(file)
WHERE type(rel) IN ['APPLIES_TO', 'GOVERNS', 'DESCRIBES', 'OBSERVED_IN', 'CHANGES', 'AFFECTS', 'RELATES_TO']
RETURN labels(memory), memory.id, memory.title, type(rel);
```

Create a decision memory:
```cypher
MERGE (p:Project {name: 'olus-dev'})
MERGE (m:Memory {project: 'olus-dev'})
MERGE (p)-[:HAS_MEMORY]->(m)
MERGE (d:Decision {id: 'DEC-parser-symbol-resolution', project: 'olus-dev'})
SET d.title = 'Use JavaParser symbol resolution',
    d.status = 'accepted',
    d.rationale = 'Resolved symbols produce stable inheritance and call edges.',
    d.updatedAt = datetime(),
    d.createdAt = coalesce(d.createdAt, datetime())
MERGE (m)-[:HAS_DECISION]->(d);
```
