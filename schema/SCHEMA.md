# Memgraph Graph Model Reference

Documentation for the Java codebase knowledge graph. The DDL lives in `schema.cypher` — this file describes the model.

## Anchor node

`(:Project)` is the anchor. Its identity is the `name` property. Every other node also carries a `project` property matching the anchor's name, so clients can filter either via the anchor traversal or via a direct property match — whichever fits the query better.

## Nodes

| Label | Key | Other properties |
|---|---|---|
| `:Project` | `name` | `sourceRoot`, `lastIngested` |
| `:Package` | `name`, `project` | — |
| `:File` | `path`, `project` | `lastModified` |
| `:Class` | `fqn`, `project` | `name`, `packageName`, `isAbstract`, `visibility` |
| `:Interface` | `fqn`, `project` | `name`, `packageName` |
| `:Method` | `signature`, `project` | `name`, `returnType`, `visibility`, `isStatic`, `startLine`, `endLine` |
| `:Field` | `fqn`, `project` | `name`, `type`, `visibility`, `isStatic` |

## Relationships

```
(Project)   -[:CONTAINS]->  (Package)
(Project)   -[:CONTAINS]->  (File)
(Package)   -[:CONTAINS]->  (Class | Interface)
(File)      -[:DEFINES]->   (Class | Interface)
(Class)     -[:EXTENDS]->   (Class)
(Class)     -[:IMPLEMENTS]->(Interface)
(Interface) -[:EXTENDS]->   (Interface)
(Class | Interface) -[:DECLARES]-> (Method | Field)
(Method)    -[:CALLS]->     (Method)
```

## Notes on constraints

- No existence constraints are enforced. Earlier versions used them, but they caused failures when ingesting partial graphs or when external types were referenced without a `project`. The composite uniqueness constraints are sufficient — the ingester always sets `project`.
- `:Project` uses a single-property uniqueness constraint because its name is globally unique. Everything else is composite `(key, project)`.

## Common queries

List all projects:
```cypher
MATCH (p:Project) RETURN p.name, p.sourceRoot, p.lastIngested;
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