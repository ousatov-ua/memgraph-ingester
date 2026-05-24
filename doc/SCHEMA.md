# Memgraph Graph Model Reference

Documentation for the codebase and Memory knowledge graph. The DDL lives in
`src/main/resources/io/github/ousatov/tools/memgraph/cypher/create-schema.cypher` — this file
describes the model.

## Anchor nodes

`(:Project)` is the project anchor. Its identity is the `name` property. Code-specific graph data
hangs below `(:Project)-[:CONTAINS]->(:Language)-[:CONTAINS]->(:Code)`. Language nodes are named
`Java` or `Js`; `:Code` and every code node carry a `project` property matching the project name,
so clients can filter either via anchor traversal or direct property matches.

Memory data hangs below `(:Project)-[:HAS_MEMORY]->(:Memory)`. `:Memory` and every memory item
carry the same `project` property. Code nodes are produced by the ingester; memory nodes are
intended for durable agent/client-authored decisions, context, and follow-up work.

## Code nodes

| Label | Key | Other properties |
|---|---|---|
| `:Project` | `name` | — |
| `:Language` | `project`, `name` | `graphName` |
| `:Code` | `project`, `language` | `languageName`, `sourceRoots` (string array), `lastIngested` |
| `:Package` | `name`, `project`, `language` | — |
| `:File` | `path`, `project` | `lastModified` (epoch millis), `language` |
| `:Class` | `fqn`, `project` | `name`, `packageName`, `isAbstract`, `visibility`, `isEnum`, `isRecord`, `isFinal`, `isExternal`, `language`, `kind`, `modulePath`, `framework` |
| `:Interface` | `fqn`, `project` | `name`, `packageName`, `visibility`, `isFinal`, `isExternal`, `language`, `kind`, `modulePath`, `framework` |
| `:Annotation` | `fqn`, `project` | `name`, `packageName`, `visibility`, `isExternal`, `language`, `kind`, `modulePath`, `framework` |
| `:Method` | `signature`, `project` | `name`, `returnType`, `visibility`, `isStatic`, `startLine`, `endLine`, `isSynthetic`, `ownerFqn`, `ownerDisplayName`, `language`, `kind` |
| `:Field` | `fqn`, `project` | `name`, `type`, `visibility`, `isStatic`, `language`, `kind` |
| `:PendingCall` | `project`, `callerSignature`, `calleeOwnerFqn`, `calleeName` | Temporary owner/name call record resolved after ingestion |

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
| `:CodeRef` | `project`, `targetType`, `key` | Stable code reference resolved after ingestion |

## Code relationships

```
(Project)   -[:CONTAINS]->      (Language)
(Language)  -[:CONTAINS]->      (Code)
(Code)      -[:CONTAINS]->      (Package)
(Code)      -[:CONTAINS]->      (File)
(Package)   -[:CONTAINS]->      (Class | Interface | Annotation)
(File)      -[:DEFINES]->       (Class | Interface | Annotation | Method | Field)
(Class)     -[:EXTENDS]->       (Class)
(Interface) -[:EXTENDS]->       (Interface)
(Class)     -[:IMPLEMENTS]->    (Interface)
(Class | Interface | Annotation) -[:DECLARES]-> (Method | Field)
(Method)    -[:CALLS]->         (Method)
(Method)    -[:PENDING_CALL]->  (PendingCall)
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

(Decision | ADR | Rule | Context | Finding | Task | Risk | Idea) -[:REFERS_TO]-> (CodeRef)
(CodeRef) -[:RESOLVES_TO]-> (Code | Package | File | Class | Interface | Annotation | Method | Field)
```

`CodeRef.targetType` is one of `Code`, `Package`, `File`, `Class`, `Interface`, `Annotation`,
`Method`, or `Field`. `CodeRef.key` uses the target identity: reference language key for `Code`
(`java` or `js`), language-prefixed package name for `Package` (`java:<package>` or
`js:<package>`), path for `File`, FQN for `Class`/`Interface`/`Annotation`/`Field`, and
signature for `Method`. The ingester deletes and recreates `RESOLVES_TO` edges after each run.

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
- `:Project` and `:Memory` use a single-property uniqueness constraint. `:Language` and `:Code`
  are unique per project language. Code and memory item nodes use composite `(key, project)`
  uniqueness unless the table above lists language as part of the key. `:CodeRef` is unique by
  `(project, targetType, key)`.
- Nested/inner classes use `$` as separator in FQN (e.g. `com.example.Outer$Inner`).
- `language`, `kind`, `modulePath`, and `framework` are optional compatibility metadata. Current
  emitted language values are `java` and `js`; older ingestions may not have these
  properties. Language grouping nodes use display names `Java` and `Js`.
- JavaScript/TypeScript modules are represented as synthetic `:Class` nodes with
  `language = "js"` and `kind = "module"`. Top-level functions and variables are declared
  by the module owner. TypeScript interfaces and type aliases reuse `:Interface`; decorators reuse
  `:Annotation` and `ANNOTATED_WITH`. TypeScript enums reuse `:Class` with `isEnum = true` and
  `kind = "enum"`. Angular decorators can set `framework = "angular"`.
- JavaScript/TypeScript file discovery is bounded by the configured `--source` root. Use the
  repository root as `--source` when root-level config or support files should be code nodes.
  `node_modules` is still skipped.
- Regular and watch re-ingestion prune deleted files and removed declarations. Changed-file
  cleanup and replacement writes are per-file transactional and preserve retained files from other
  source roots. Watch re-ingestion snapshots source files once per batch and skips delete cleanup
  if file updates fail.
- JavaScript/TypeScript class and interface heritage is represented with the shared `EXTENDS` and
  `IMPLEMENTS` relationships. Relative imports and `tsconfig.json` path aliases, including aliases
  inherited through extended configs, that resolve under the ingested source root can point
  relations at internal type FQNs; unresolved imports fall back to external/simple FQNs.
- JavaScript/TypeScript interface members and object-literal type members are emitted as
  `:Field`/`:Method` declarations. Bodyless abstract or optional class method signatures are
  emitted as `:Method` declarations so abstract APIs are visible.
- Java and TypeScript enum constants/members are emitted as `:Field` declarations with
  `kind = "enum-member"`.
- JavaScript/TypeScript function-valued class fields are emitted as `:Method` records for callable
  inventories and receiver-scoped `CALLS` resolution. They can also appear as `:Field` records
  because the source member is still a class field.
- JavaScript/TypeScript named class expressions and class expressions assigned to variables are
  emitted as `:Class` nodes. Variable-assigned class expressions use the variable name. Relative
  imports and `tsconfig.json` path aliases, including aliases inherited through extended configs,
  that resolve to local source files can produce owner/name `CALLS` edges when the target owner has
  exactly one method with the imported name.
- JavaScript/TypeScript exported callable aliases such as `export { foo as bar }`,
  `export { foo as bar } from "./mod"`, and `export default foo` are emitted as graph-visible
  declarations for the public export names so deferred owner/name call resolution can match imports
  by exported name. Class re-export aliases are emitted as `:Class` nodes with constructor
  declarations so `new X()` imports from barrel modules can resolve through the alias.
- JavaScript/TypeScript namespace-qualified decorators preserve the namespace in the annotation FQN
  when the namespace import can be identified.
- `CALLS` edges only connect methods within the same project. External library calls are dropped to avoid phantom nodes. JavaScript/TypeScript owner/name calls that cross file-order boundaries are first stored as `:PendingCall` records, then resolved after the ingestion batch. Direct owner methods are preferred, then the nearest superclass with exactly one matching method. Unresolved or ambiguous pending calls can remain until a later ingestion supplies a unique target; pending calls for a reingested JS/TS file are cleared before the file's current calls are stored.
- JavaScript/TypeScript `CALLS` edges are syntax-only best effort. Top-level IIFEs/callbacks and
  local function constructors are handled, but dynamic dispatch, framework templates, dependency
  injection, monkey-patching, and generated code can be missing.
- **External / phantom nodes.** When a class extends or implements an external type, the parent node is created with `isExternal = true`. Its `name` and `packageName` are inferred from the FQN. External annotations (those not defined in the ingested source tree) are also marked `isExternal = true`. Project-internal nodes always have `isExternal = false`. Use `WHERE NOT n.isExternal` to exclude external types from queries.
- Memory relationships are conventional, not constrained by DDL. Agents should keep memory scoped with `project` and link memory to `:CodeRef`, not directly to code nodes.

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
MATCH (p:Project)-[:CONTAINS]->(l:Language)-[:CONTAINS]->(c:Code)
RETURN p.name, l.name, c.sourceRoots, c.lastIngested;
```

All classes in a project:
```cypher
MATCH (:Project {name: 'olus-dev'})-[:CONTAINS]->(:Language {name: 'Java'})
      -[:CONTAINS]->(:Code)-[:CONTAINS]->(:File)-[:DEFINES]->(c:Class)
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
MATCH (memory {project: 'olus-dev'})-[:REFERS_TO]->(:CodeRef)-[:RESOLVES_TO]->(file)
RETURN labels(memory), memory.id, memory.title;
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

Create a decision memory that refers to a class:
```cypher
MERGE (m:Memory {project: 'olus-dev'})
MERGE (d:Decision {id: 'DEC-parser-symbol-resolution', project: 'olus-dev'})
SET d.title = 'Use JavaParser symbol resolution',
    d.status = 'accepted',
    d.updatedAt = datetime(),
    d.createdAt = coalesce(d.createdAt, datetime())
MERGE (ref:CodeRef {project: 'olus-dev', targetType: 'Class', key: 'com.example.Widget'})
MERGE (m)-[:HAS_DECISION]->(d)
MERGE (d)-[:REFERS_TO]->(ref);
```
