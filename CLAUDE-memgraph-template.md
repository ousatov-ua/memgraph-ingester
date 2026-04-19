## Code knowledge graph (Memgraph MCP)

<!--
  TEMPLATE: replace {{PROJECT_NAME}} with the actual Memgraph project name
  used at ingestion time, e.g. `work`, `olus-platform`.

  Quick replace:
      sed -i '' 's/{{PROJECT_NAME}}/work/g' CLAUDE.md

  If you leave the placeholder unreplaced, Claude will ask for the name
  on the first turn and use it for the rest of the session.
-->

This repo is indexed in Memgraph under the project name **`{{PROJECT_NAME}}`**.

For questions about code structure, dependencies, call graphs, inheritance,
or any cross-file relationship, **query the graph via the Memgraph MCP
server first**. It's faster than filesystem search and resolves symbols
properly (type inference, fully-qualified names, inherited members).

### Required scoping

The Memgraph instance hosts multiple projects. **Every query must be
scoped to `{{PROJECT_NAME}}`** via one of these patterns:

- Direct property filter (preferred for simple lookups):
  ```cypher
  MATCH (c:Class {project: '{{PROJECT_NAME}}'}) ...
  ```

- Anchor traversal (preferred when exploring from a starting point):
  ```cypher
  MATCH (:Project {name: '{{PROJECT_NAME}}'})-[:CONTAINS*]->(x) ...
  ```

Never run unfiltered queries like `MATCH (c:Class) RETURN c` — results
will include other projects.

### Graph schema

**Nodes**

| Label | Key | Notable properties |
|---|---|---|
| `:Project` | `name` | `sourceRoot`, `lastIngested` |
| `:Package` | `(name, project)` | — |
| `:File` | `(path, project)` | — |
| `:Class` | `(fqn, project)` | `name`, `packageName`, `isAbstract` |
| `:Interface` | `(fqn, project)` | `name`, `packageName` |
| `:Method` | `(signature, project)` | `name`, `returnType`, `isStatic`, `startLine`, `endLine` |
| `:Field` | `(fqn, project)` | `name`, `type`, `isStatic` |

**Relationships**

```
(:Project)          -[:CONTAINS]->   (:Package | :File)
(:Package)          -[:CONTAINS]->   (:Class | :Interface)
(:File)             -[:DEFINES]->    (:Class | :Interface)
(:Class)            -[:EXTENDS]->    (:Class)
(:Class)            -[:IMPLEMENTS]-> (:Interface)
(:Interface)        -[:EXTENDS]->    (:Interface)
(:Class|:Interface) -[:DECLARES]->   (:Method | :Field)
(:Method)           -[:CALLS]->      (:Method)
```

### Query recipes

**Discover available projects (if `{{PROJECT_NAME}}` is unset)**
```cypher
MATCH (p:Project) RETURN p.name, p.sourceRoot, p.lastIngested;
```

**All implementations of an interface**
```cypher
MATCH (i:Interface {name: 'OrderRepository', project: '{{PROJECT_NAME}}'})
      <-[:IMPLEMENTS]-(c:Class)
RETURN c.fqn;
```

**Callers of a method**
```cypher
MATCH (caller:Method)-[:CALLS]->(m:Method {name: 'save', project: '{{PROJECT_NAME}}'})
RETURN DISTINCT caller.signature
ORDER BY caller.signature;
```

**Methods of a class**
```cypher
MATCH (c:Class {fqn: 'com.example.OrderService', project: '{{PROJECT_NAME}}'})
      -[:DECLARES]->(m:Method)
RETURN m.name, m.returnType, m.startLine
ORDER BY m.startLine;
```

**Full inheritance chain**
```cypher
MATCH path = (c:Class {name: 'OrderServiceImpl', project: '{{PROJECT_NAME}}'})
             -[:EXTENDS*]->(ancestor:Class)
RETURN [n IN nodes(path) | n.fqn] AS chain;
```

**Transitive callers (who eventually reaches this method?)**
```cypher
MATCH (caller:Method)-[:CALLS*1..5]->(m:Method {name: 'deleteAll', project: '{{PROJECT_NAME}}'})
RETURN DISTINCT caller.signature;
```

**Classes in a package**
```cypher
MATCH (:Package {name: 'com.example.order', project: '{{PROJECT_NAME}}'})
      -[:CONTAINS]->(c:Class)
RETURN c.name
ORDER BY c.name;
```

### When NOT to use the graph

The graph only knows Java AST structure. Use filesystem tools for:

- Reading actual source code (method bodies, comments, Javadoc)
- Searching string literals, log messages, or TODOs
- Anything in non-Java files: YAML, properties, SQL, Dockerfiles, `pom.xml`
- Anything the parser skipped (annotation processors, generated code)

Rule of thumb: **graph answers "what's related to what"; filesystem
answers "what does it actually do".** Usually you'll use both — graph
to find relevant files, filesystem to read them.

### Caveats

- `CALLS` edges are best-effort. JavaParser can't always resolve callees
  (external libs, generics, lambdas), so transitive call queries may
  have gaps. Missing edges don't mean no call exists.
- `DECLARES` is used for both methods and fields. Always filter by label
  when you only want one: `-[:DECLARES]->(m:Method)` not `-[:DECLARES]->()`.

### Staleness

The graph can drift after code changes. Check freshness with:

```cypher
MATCH (p:Project {name: '{{PROJECT_NAME}}'}) RETURN p.lastIngested;
```

Re-ingestion is a separate tool — ask me to run it rather than attempting
to trigger it from Claude Code.
