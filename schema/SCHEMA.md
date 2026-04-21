# Memgraph Graph Model Reference

Documentation for the Java codebase knowledge graph. The DDL lives in `schema.cypher` — this file describes the model.

## Anchor node

`(:Project)` is the anchor. Its identity is the `name` property. Every other node also carries a `project` property matching the anchor's name, so clients can filter either via the anchor traversal or via a direct property match — whichever fits the query better.

## Nodes

| Label | Key | Other properties |
|---|---|---|
| `:Project` | `name` | `sourceRoots` (string array), `lastIngested` |
| `:Package` | `name`, `project` | — |
| `:File` | `path`, `project` | `lastModified` (epoch millis) |
| `:Class` | `fqn`, `project` | `name`, `packageName`, `isAbstract`, `visibility`, `isEnum`, `isRecord` |
| `:Interface` | `fqn`, `project` | `name`, `packageName`, `visibility` |
| `:Annotation` | `fqn`, `project` | `name`, `packageName`, `visibility` |
| `:Method` | `signature`, `project` | `name`, `returnType`, `visibility`, `isStatic`, `startLine`, `endLine` |
| `:Field` | `fqn`, `project` | `name`, `type`, `visibility`, `isStatic` |

## Relationships

```
(Project)   -[:CONTAINS]->      (Package)
(Project)   -[:CONTAINS]->      (File)
(Package)   -[:CONTAINS]->      (Class | Interface | Annotation)
(File)      -[:DEFINES]->       (Class | Interface | Annotation)
(Class)     -[:EXTENDS]->       (Class)
(Interface) -[:EXTENDS]->       (Interface)
(Class)     -[:IMPLEMENTS]->    (Interface)
(Class | Interface) -[:DECLARES]-> (Method | Field)
(Method)    -[:CALLS]->         (Method)
(Class | Interface | Annotation | Method | Field) -[:ANNOTATED_WITH]-> (Annotation)
```

## Notes on constraints

- No existence constraints are enforced. Earlier versions used them, but they caused failures when ingesting partial graphs or when external types were referenced without a `project`. The composite uniqueness constraints are sufficient — the ingester always sets `project`.
- `:Project` uses a single-property uniqueness constraint because its name is globally unique. Everything else is composite `(key, project)`.
- Nested/inner classes use `$` as separator in FQN (e.g. `com.example.Outer$Inner`).
- `CALLS` edges only connect methods within the same project. External library calls are dropped to avoid phantom nodes. A second wipe-less re-ingestion pass fills in any cross-file edges missed due to ingestion ordering.

## Common queries

List all projects:
```cypher
MATCH (p:Project) RETURN p.name, p.sourceRoots, p.lastIngested;
```

All classes in a project:
```cypher
MATCH (:Project {name: 'olus-dev'})-[:CONTAINS]->(:File)-[:DEFINES]->(c:Class)
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
