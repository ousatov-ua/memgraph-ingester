## Knowledge Graph

Repo is indexed in Memgraph as **`{{PROJECT_NAME}}`**. Every query MUST include
`project: '{{PROJECT_NAME}}'`.

### Lookup Order

Use this order for repository knowledge:

1. **Memgraph** for structure, relationships, and code metadata.
2. **Source files** for line-level detail only.
3. **grep/glob** for strings, comments, templates, and resources not represented in the graph.
4. **Other tools** only as a last resort.

When Memgraph returns no relevant rows, fall back to text search and state why.

### Mandatory Triggers

- **NO DELEGATION:** Never delegate architecture analysis, codebase investigations, or member/caller lookups to subagents (e.g., `codebase_investigator`). You MUST use Memgraph.
- **Status/pending-work requests:** query Memgraph staleness or relevant structure first, then check Git when local changes are relevant. Never answer from Git alone unless the user explicitly asks for Git-only status.
- **No ritual Codebase Analysis:** do not run Codebase Analysis queries just to have context. Run them only when a trigger below applies or code structure/relationships are needed.
- **Orientation reuse:** Memgraph query results are session-scoped. Reuse relevant results unless the user asks for a refresh, source files changed, memory changed, or the task scope is unrelated.
- **Relationship refresh after edits:** if source files changed during the session, re-query Memgraph relationships before relying on earlier relationship results; live ingestion may make cached relationships stale.
- **Code changes:** before source-code changes, run the smallest useful Memgraph query set. Use focused symbol/file/method queries for known targets. Run full Codebase Analysis only for broad, ambiguous, cross-cutting, inheritance-heavy, or unfamiliar-subsystem work. Empty results are valid; fall back to text search and state why.
- **Class/interface work:** query hierarchy before changing class/interface declarations, inheritance, `implements`/`extends`, constructor contracts, or overridden/inherited APIs. For small body-only edits inside a known class, hierarchy lookup is optional unless inheritance could affect behavior.
- **Symbol work:** for investigations involving symbols, fields, methods, callers, implementations, inheritance, decorators/annotations, imports, exports, or type usages, query Memgraph before source inspection, filesystem search, IDE/LSP, or runtime introspection. JavaScript/TypeScript and Python CALLS edges are best-effort.
- **Method body reads:** when the target method is known, first query `startLine` and `endLine`, then read only that source range. If the method is not known, use a focused symbol query by class/name before reading source.

## Memgraph Access

### Tool Order

For Cypher queries:

1. Use an MCP tool whose name contains `memgraph` or `cypher` if available.
2. Otherwise, use `mgconsole` with `--no_history --output-format=csv`.

Report which query tool was used.

### `mgconsole` Rules

**BLOCKING:** Use one interactive `mgconsole --no_history --output-format=csv` session per task and reuse it for all Memgraph queries until the task is finished. Close it with `:quit` before final response.

Start command:

```bash
mgconsole --host ${MG_HOST:-127.0.0.1} --port ${MG_PORT:-7687} ${MG_USER:+--username $MG_USER} ${MG_PASS:+--password $MG_PASS} --output-format=csv --no_history
```

Interactive TTY submission:
- Run one Cypher statement at a time.
- End every statement with `;`.
- Submit lines with carriage return (`\r`); LF-only multiline paste may not execute.
- Empty output means 0 rows, not an error.
- Large results must be paginated in Cypher with `ORDER BY`, `SKIP`, and `LIMIT`; do not post-process with shell tools.
- If `mgconsole` is missing, locate it with `which mgconsole || find /opt /usr/local -name mgconsole 2>/dev/null | head -1`.

**HARD RULE:** Never pass Cypher as a direct `mgconsole` argument.

```bash
# Wrong
mgconsole [options] "MATCH (n) RETURN n;"

# Correct non-interactive form, only when a single-query workflow is explicitly appropriate
echo "MATCH (n) RETURN n;" | mgconsole [options]
```

## Tagged Files

`@`-tagged paths hint at scope only; they do not bypass Memgraph.

Before reading any tagged source file or directory for code work:

1. Run focused Memgraph queries when code structure or relationships are relevant.
2. Then open source files for line-level detail.

## Codebase Analysis Queries

```cypher
// Package boundaries
MATCH (p:Package {project: '{{PROJECT_NAME}}'})
RETURN p.language, p.name ORDER BY p.language, p.name;

// Class inventory
MATCH (p:Package {project: '{{PROJECT_NAME}}'})-[:CONTAINS]->(c:Class)
WHERE c.isExternal = false
RETURN p.language AS language, p.name AS pkg, c.name AS cls, c.isAbstract, c.isFinal
ORDER BY language, p.name, c.name;

// Cross-class call graph
MATCH (caller:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
WHERE caller.ownerFqn IS NOT NULL
  AND callee.ownerFqn IS NOT NULL
  AND caller.ownerFqn <> callee.ownerFqn
WITH caller.ownerDisplayName + ' -> ' + callee.ownerDisplayName AS edge, COUNT(*) AS n
RETURN edge, n ORDER BY n DESC LIMIT 30;

// Method-count hotspots
MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:DECLARES]->(m:Method)
WHERE c.isExternal = false AND m.isSynthetic = false
WITH c.fqn AS cls, COUNT(m) AS n
RETURN cls, n ORDER BY n DESC LIMIT 20;

// Interface implementors
MATCH (i:Interface {project: '{{PROJECT_NAME}}'})
OPTIONAL MATCH (c:Class {project: '{{PROJECT_NAME}}', isExternal: false})-[:IMPLEMENTS]->(i)
WITH i.fqn AS iface, collect(c.fqn) AS implementors
RETURN iface, implementors ORDER BY iface;

// Annotation usage
MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:ANNOTATED_WITH]->(a:Annotation)
WHERE c.isExternal = false
WITH a.fqn AS ann, COUNT(c) AS n
RETURN ann, n ORDER BY n DESC LIMIT 20;

// Non-static fields
MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:DECLARES]->(f:Field)
WHERE c.isExternal = false AND f.isStatic = false
RETURN c.fqn AS cls, f.name, f.type, f.visibility
ORDER BY c.fqn, f.name;
```

## Focused Memgraph Queries

For narrow known-target edits, prefer focused queries:

```cypher
// Find a type by simple name
MATCH (t {project: '{{PROJECT_NAME}}'})
WHERE (t:Class OR t:Interface) AND t.name = '<TypeName>'
RETURN labels(t) AS labels, t.fqn AS fqn, t.kind AS kind, t.modulePath AS modulePath
ORDER BY fqn LIMIT 20;

// Find members of a known class
MATCH (c:Class {project: '{{PROJECT_NAME}}', fqn: '<fqn>'})-[:DECLARES]->(m:Method)
RETURN m.signature, m.name, m.startLine, m.endLine, m.returnType, m.visibility
ORDER BY m.name;

MATCH (c:Class {project: '{{PROJECT_NAME}}', fqn: '<fqn>'})-[:DECLARES]->(f:Field)
RETURN f.fqn, f.name, f.type, f.visibility, f.isStatic
ORDER BY f.name;

// Find callers of a known method or owner
MATCH (caller:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
WHERE callee.signature CONTAINS '<signature-or-owner-fragment>'
RETURN caller.signature, caller.ownerDisplayName, caller.startLine, caller.endLine
ORDER BY caller.signature LIMIT 100;
```

Use full Codebase Analysis only when focused queries do not identify the target or broad relationship context is needed.

## Code RAG Vectors (only if RAG has embeddings)

Use `:CodeChunk` only for broad or fuzzy code discovery. After any vector hit, return to exact
Memgraph structure queries and source ranges before making claims or edits.

Check whether a compatible vector index exists:

```cypher
SHOW VECTOR INDEX INFO;
```

Search semantically similar code chunks with a query vector created by the same embedding model and
dimension as stored chunks:

```cypher
CALL vector_search.search('code_chunk_embedding_v1', 10, $queryVector)
YIELD node AS chunk, similarity
WHERE chunk.project = '{{PROJECT_NAME}}'
MATCH (source {project: '{{PROJECT_NAME}}'})-[:HAS_RAG_CHUNK]->(chunk)
RETURN labels(source) AS sourceType, chunk.sourceId AS sourceId,
       chunk.path AS path, chunk.ownerFqn AS ownerFqn, chunk.signature AS signature,
       chunk.text AS text, similarity
ORDER BY similarity DESC;
```

`CodeChunk` text is derived search material. It should include language, path, symbol name, owner,
signature, documentation comments attached to the code symbol (JavaDoc for Java), and a bounded
source excerpt.

The ingester creates and refreshes `CodeChunk` rows during successful re-ingest and watch-mode file
updates. Agents should not hand-author `CodeChunk` rows; use them only for semantic discovery, then
verify against canonical Code nodes and source ranges.

When ingestion runs with `--code-embeddings`, the ingester uses Memgraph's
`embeddings.node_sentence()` procedure to refresh stale `CodeChunk.embedding` values and creates the
`code_chunk_embedding_v1` vector index when needed.

## Schema

### Code Nodes

| Label         | Key                    | Notable properties                                                                                                    |
|---------------|------------------------|-----------------------------------------------------------------------------------------------------------------------|
| `:Project`    | `name`                 | -                                                                                                                     |
| `:Language`   | `(project, name)`      | `graphName`                                                                                                           |
| `:Code`       | `(project, language)`  | `lastIngested`, `languageName`                                                                                        |
| `:Package`    | `(name, project, language)` | -                                                                                                                |
| `:File`       | `(path, project)`      | `lastModified`, `language`                                                                                            |
| `:Class`      | `(fqn, project)`       | `name`, `isAbstract`, `isEnum`, `isRecord`, `isFinal`, `isExternal`, `visibility`, `language`, `kind`, `modulePath`, `framework` |
| `:Interface`  | `(fqn, project)`       | `name`, `visibility`, `isFinal`, `isExternal`, `language`, `kind`, `modulePath`, `framework`                          |
| `:Annotation` | `(fqn, project)`       | `name`, `visibility`, `isExternal`, `language`, `kind`, `modulePath`, `framework`                                     |
| `:Method`     | `(signature, project)` | `name`, `ownerFqn`, `ownerDisplayName`, `returnType`, `visibility`, `isStatic`, `startLine`, `endLine`, `isSynthetic`, `language`, `kind` |
| `:Field`      | `(fqn, project)`       | `name`, `type`, `visibility`, `isStatic`, `language`, `kind`                                                          |
| `:PendingCall`| `(project, callerSignature, calleeOwnerFqn, calleeName)` | temporary owner/name call record resolved after ingestion                                      |
| `:CodeChunk`  | `(id, project)`        | derived RAG text/vector node: `sourceLabel`, `sourceId`, `language`, `path`, `ownerFqn`, `signature`, `text`, `textHash`, `embedding`, `embeddingModel`, `embeddingDimensions` |

### Code Relationships

```text
(:Project)-[:CONTAINS]->(:Language)-[:CONTAINS]->(:Code)-[:CONTAINS]->(:Package|:File)
(:Package)-[:CONTAINS]->(:Class|:Interface|:Annotation)
(:File)-[:DEFINES]->(:Class|:Interface|:Annotation|:Method|:Field)
(:Class)-[:EXTENDS]->(:Class)
(:Class)-[:IMPLEMENTS]->(:Interface)
(:Interface)-[:EXTENDS]->(:Interface)
(:Class|:Interface|:Annotation)-[:DECLARES]->(:Method|:Field)
(:Method)-[:CALLS]->(:Method)
(:Method)-[:PENDING_CALL]->(:PendingCall)
(:*)-[:ANNOTATED_WITH]->(:Annotation)
(:Code|:Package|:File|:Class|:Interface|:Annotation|:Method|:Field)-[:HAS_RAG_CHUNK]->(:CodeChunk)
```

### Query Caveats

- Code is grouped by `(:Language)-[:CONTAINS]->(:Code)` between `:Project` and source nodes. First-class language graph values are `java`, `js`, and `python`; ctags fallback ingestion can add detected language values such as `ruby`, `go`, `rust`, or `kotlin`. Code graph nodes may have optional `language`, `kind`, `modulePath`, and `framework` metadata. Older graphs may not have these properties.
- `CALLS` has no `project`; filter both endpoints.
- `CALLS` and `ANNOTATED_WITH` are best-effort; missing edges do not prove no relationship.
- JavaScript/TypeScript modules are represented as synthetic `:Class` owner nodes with `language = "js"` and `kind = "module"`. Top-level functions and variables are declared by that module owner.
- Python modules are represented as synthetic `:Class` owner nodes with `language = "python"` and `kind = "module"`. Top-level functions and variables are declared by that module owner.
- Ctags-detected modules are represented as synthetic `:Class` owner nodes with the detected `language` and `kind = "module"`. Ctags fallback emits file/package/module/type/member inventories only; it does not promise call graphs, inheritance, imports, decorators, or language-specific semantic resolution.
- Raw JavaScript/TypeScript `:Class` queries include synthetic module owners and TypeScript enums. Filter by `kind = "class"` when you only want classes.
- JavaScript/TypeScript file discovery is bounded by the configured `--source` root. If `--source` points at `src`, root-level config or support files such as `jest.config.ts`, `webpack.config.ts`, `karma.conf.js`, or `mocker/*.js` are outside the ingested tree. Use the repository root as `--source` when those files should be code nodes. `node_modules` is still skipped.
- Python file discovery is bounded by the configured `--source` root and skips common environment/cache directories such as `.venv`, `venv`, `site-packages`, `__pycache__`, `build`, and `dist`.
- JavaScript/TypeScript classes reuse `:Class`; TypeScript interfaces and type aliases reuse `:Interface`; decorators reuse `:Annotation` plus `ANNOTATED_WITH`.
- JavaScript/TypeScript class/interface heritage uses the shared `EXTENDS` and `IMPLEMENTS` relationships. Relative imports and `tsconfig.json` path aliases, including aliases inherited through extended configs, that resolve under the ingested source root can point relations at internal type FQNs; unresolved imports fall back to external/simple FQNs.
- JavaScript/TypeScript interface members and object-literal type members are emitted as `:Field`/`:Method` declarations. Bodyless abstract or optional class method signatures are emitted as `:Method` declarations so abstract APIs are visible.
- Java and TypeScript enum constants/members are emitted as `:Field` declarations with `kind = "enum-member"`.
- Angular decorators such as `Component`, `Directive`, `Injectable`, `NgModule`, and `Pipe` may set `framework = "angular"` on the decorated type.
- JavaScript/TypeScript function-valued class fields are emitted as callable `:Method` records and can also appear as `:Field` records.
- JavaScript/TypeScript named class expressions and class expressions assigned to variables are emitted as `:Class` nodes. Variable-assigned class expressions use the variable name. Relative imports and `tsconfig.json` path aliases, including aliases inherited through extended configs, that resolve to local source files can produce owner/name `CALLS` edges when the target owner has exactly one method with the imported name.
- JavaScript/TypeScript exported callable aliases such as `export { foo as bar }`, `export { foo as bar } from "./mod"`, and `export default foo` are emitted as graph-visible declarations for their public export names so deferred owner/name call resolution can match imports by exported name. Class re-export aliases are emitted as `:Class` nodes with constructor declarations so `new X()` imports from barrel modules can resolve through the alias.
- JavaScript/TypeScript namespace-qualified decorators preserve the namespace in the annotation FQN when the namespace import can be identified.
- JavaScript/TypeScript and Python owner/name fallback calls can be stored as `:PendingCall` records during ingestion and resolved after the batch. Direct owner methods are preferred, then the nearest superclass with exactly one matching method. Unresolved or ambiguous pending calls can remain until later ingestion; pending calls for a reingested JS/TS or Python file are cleared before the file's current calls are stored.
- Regular and watch re-ingestion prune deleted files and removed declarations. Retained snapshots include active-source files, existing same-root graph files, and existing files from other source roots. Re-ingestion refreshes retained files after deletes with the retained file's source root. Watch re-ingestion also skips delete cleanup after update failures, retries snapshot-failed batches, and reconciles delete-only snapshot failures.
- JavaScript/TypeScript `CALLS` edges are syntax-only and intra-project best effort, not a complete raw AST call inventory. Identifier calls resolve only when the local declaration name is unique. Property calls resolve only for known local receivers such as `this`, typed `this.<property>` receivers for local classes, a local class, or `new LocalClass()`. Constructor calls from `new LocalClass()` and local function constructors resolve to explicit or synthesized signatures; imported or barrel class constructors use owner/name pending calls. Top-level IIFEs and callback bodies are traversed, while standalone nested functions are skipped. Unknown receivers, dynamic dispatch, dependency injection, framework templates, monkey-patching, and generated code can be missing.
- Python `CALLS` edges are syntax-only and intra-project best effort from CPython `ast`. Local function calls and resolvable `self.method()` calls are handled, while dynamic dispatch, monkey-patching, imports outside `--source`, and generated code can be missing.
- JavaScript/TypeScript `packageName` and module owner FQN values are synthetic, collision-safe encoded path identities with a `js.` prefix; they are not npm package names or raw filenames.
- Ctags-detected `packageName` and module owner FQN values are synthetic, collision-safe encoded path identities with the detected graph language prefix.
- Fully ingested `Method` nodes store `ownerFqn` and `ownerDisplayName`; prefer those properties for relationship summaries instead of parsing `signature` or traversing `DECLARES`.
- Placeholder callee `Method` nodes created during call-edge ingestion can lack owner metadata until the callee is ingested; phantom cleanup normally removes unresolved placeholders.
- External nodes use `isExternal = true`. External interfaces implemented by project classes still have `IMPLEMENTS` edges, but are excluded by internal-interface filters.
- Non-JDK annotation FQNs may be stored as simple names.
- Constructors use `name = '<init>'`.
- Nested class FQNs use `$` and are stored in the parent class package; static-ness is not stored.
- Record accessor methods are synthetic. Drop the synthetic filter when accessors matter. Records with no explicit methods do not appear in non-synthetic method-count results.
- Always label `DECLARES` targets, e.g. `-[:DECLARES]->(m:Method)`.
- `visibility` values are `"public"`, `"protected"`, `"private"`, or `""` for package-private.
- Prefer `c.isExternal = false` over `NOT c.isExternal` in multi-hop patterns.
- Prefer explicit labels in relationship patterns for precision and planner behavior.
- Aggregation must be in `WITH` or `RETURN`; never use aggregate functions directly in `WHERE` or `ORDER BY`.
- When a query uses aggregation, first alias grouping keys and aggregate values with `WITH`; then `RETURN`, `ORDER BY`, filter, or paginate by aliases only.
- Avoid chaining multiple `OPTIONAL MATCH` clauses after label scans when aggregates appear. Move filters into node patterns and split into separate queries if needed.
- Implicit default constructors are synthetic `<init>()` methods with `startLine=0`, `endLine=0`.

## Standard Queries

### Language Selection

When the target file language is unclear, query available graph languages first:

```cypher
MATCH (l:Language {project: '{{PROJECT_NAME}}'})-[:CONTAINS]->(c:Code)
RETURN l.name AS languageName, l.graphName AS graphName, c.language AS language
ORDER BY languageName;
```

For follow-up queries, filter by the returned `language` / `graphName` value. Ctags-ingested files
are stored under their detected language, such as `ruby`, `go`, `rust`, or `kotlin`.

### Pagination

Use stable ordering and paginate in Cypher:

```cypher
MATCH (a:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(b:Method {project: '{{PROJECT_NAME}}'})
WHERE a.signature CONTAINS 'ClassName.'
WITH a.signature AS caller, b.signature AS callee
RETURN caller, callee
ORDER BY caller
SKIP 0
LIMIT 200;
```

Recommended page sizes: 200 for Method/CALLS queries, 100 for node-with-properties queries.
If a tool saves results to a file because the result is large, re-query with tighter `WHERE` filters.

### Hierarchy

Run before class/interface work:

```cypher
MATCH (c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})
OPTIONAL MATCH (c)-[:EXTENDS]->(parent:Class {project: '{{PROJECT_NAME}}'})
OPTIONAL MATCH (c)-[:IMPLEMENTS]->(iface:Interface {project: '{{PROJECT_NAME}}'})
OPTIONAL MATCH (child:Class {project: '{{PROJECT_NAME}}'})-[:EXTENDS]->(c)
WITH c.fqn AS classFqn, collect(DISTINCT parent.fqn) AS parents,
     collect(DISTINCT iface.fqn) AS ifaces, collect(DISTINCT child.fqn) AS children
RETURN classFqn, parents, ifaces, children;

MATCH path = (c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})-[:EXTENDS*]->(a:Class {project: '{{PROJECT_NAME}}'})
RETURN [n IN nodes(path) | n.fqn] AS ancestors;

MATCH path = (child:Class {project: '{{PROJECT_NAME}}'})-[:EXTENDS*]->(c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})
RETURN [n IN nodes(path) | n.fqn] AS descendants;

MATCH path = (c:Class {fqn: 'com.example.MyClass', project: '{{PROJECT_NAME}}'})-[:EXTENDS*0..]->(:Class {project: '{{PROJECT_NAME}}'})-[:IMPLEMENTS]->(:Interface {project: '{{PROJECT_NAME}}'})-[:EXTENDS*0..]->(i:Interface {project: '{{PROJECT_NAME}}'})
RETURN DISTINCT i.fqn AS iface;

MATCH path = (i:Interface {fqn: 'com.example.MyInterface', project: '{{PROJECT_NAME}}'})-[:EXTENDS*]->(parent:Interface {project: '{{PROJECT_NAME}}'})
RETURN [n IN nodes(path) | n.fqn] AS interfaceAncestors;

MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:EXTENDS*0..]->(:Class {project: '{{PROJECT_NAME}}'})-[:IMPLEMENTS]->(:Interface {project: '{{PROJECT_NAME}}'})-[:EXTENDS*0..]->(i:Interface {fqn: 'com.example.MyInterface', project: '{{PROJECT_NAME}}'})
RETURN DISTINCT c.fqn AS implementor ORDER BY implementor;
```

### Code Search

Always include method line numbers when fetching methods.

```cypher
MATCH (c:Class {fqn: '...', project: '{{PROJECT_NAME}}'})-[:DECLARES]->(m:Method)
RETURN m.signature, m.ownerFqn, m.ownerDisplayName, m.visibility, m.returnType, m.startLine, m.endLine
ORDER BY m.name;

MATCH (caller:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
WHERE callee.signature CONTAINS 'MyClass.myMethod('
RETURN caller.signature;

MATCH (caller:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
WHERE caller.ownerFqn IS NOT NULL
  AND callee.ownerFqn IS NOT NULL
  AND caller.ownerFqn <> callee.ownerFqn
WITH caller.ownerDisplayName + ' -> ' + callee.ownerDisplayName AS edge, COUNT(*) AS cnt
RETURN edge, cnt ORDER BY cnt DESC;
```

Signature format: `pkg.ClassName.methodName(fully.qualified.ParamType, ...)`.
Constructors use `<init>`.

### Staleness

```cypher
MATCH (c:Code {project: '{{PROJECT_NAME}}'})
RETURN c.lastIngested;
```

`lastIngested` is Unix epoch microseconds.
