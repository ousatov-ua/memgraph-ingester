## Knowledge Graph

Repo is indexed in Memgraph as **`{{PROJECT_NAME}}`**. Every query MUST include
`project: '{{PROJECT_NAME}}'`.

### Lookup Order

1. **Memgraph** for structure, relationships, symbols, and code metadata.
2. **Source files** only after graph lookup, for line-level detail.
3. **grep/glob** for strings, templates, comments, and files absent from the graph.
4. **Other tools** only as a last resort.

If Memgraph returns no relevant rows, fall back to text search and say why.

### Mandatory Triggers

- **NO DELEGATION:** never delegate architecture analysis, codebase investigations, member/caller lookups, or graph queries to subagents. Use Memgraph yourself.
- **Status/pending-work:** query Memgraph staleness or relevant structure first, then check Git when local changes matter. Use Git alone only when explicitly asked for Git-only status.
- **No ritual analysis:** run Codebase Analysis queries only when broad structure is needed. Prefer focused queries.
- **Reuse:** reuse session-scoped graph results unless source files changed, the user asks for refresh, memory changed, or scope changed.
- **RAG-first for broad/unfamiliar code:** use Code RAG for implementation/debugging/refactoring entry points, "how is this implemented", and similar-code discovery. Use Memory RAG for prior work/task history. RAG hits are discovery only: resolve useful hits to canonical Code nodes and exact source ranges before claims or edits.
- **Known targets:** skip RAG for known class/file/method/signature targets. Use exact queries for members, callers/callees, hierarchy, imports/exports, annotations, fields, and source ranges.
- **Hypothesis-driven RAG:** for broad/ambiguous work, create 1-3 concise semantic queries from your hypotheses, observed symbols/errors, likely mechanisms, failure modes, or verified clues. Each query must aim at one next exact lookup or source read. After two unhelpful RAG searches for the same question, stop and use exact Memgraph or text search; reset only after exact verification changes the question or reveals a concrete clue. Do not chain RAG searches from RAG hits alone. Briefly report prompts that materially shaped the result.
- **Before source-code changes:** use RAG-first when broad/unfamiliar; use the smallest exact query set when known. Run full Codebase Analysis only for cross-cutting, inheritance-heavy, or unfamiliar-subsystem work.
- **Class/interface changes:** query hierarchy before changing declarations, inheritance, `implements`/`extends`, constructors, or overridden/inherited APIs. Small body-only edits inside a known class do not require hierarchy unless inheritance may affect behavior.
- **Symbol work:** query Memgraph before source inspection for symbols, methods, fields, callers, implementations, imports/exports, annotations/decorators, and type usages. JavaScript/TypeScript/Python `CALLS` edges are best-effort.
- **Method bodies:** when a method is known, query `startLine`/`endLine` first, then read only that range.
- **After edits:** if source changed and you need relationships again, re-query Memgraph because live ingestion may have refreshed the graph.

## Memgraph Access

Use an MCP tool whose name contains `memgraph` or `cypher` when available. Otherwise use one
interactive `mgconsole --no_history --output-format=csv` session per task, reuse it, and close it
with `:quit` before final response. Report which query tool you used.

Start `mgconsole`:

```bash
mgconsole --host ${MG_HOST:-127.0.0.1} --port ${MG_PORT:-7687} ${MG_USER:+--username $MG_USER} ${MG_PASS:+--password $MG_PASS} --output-format=csv --no_history
```

`mgconsole` rules: one Cypher statement at a time; end with `;`; submit interactive lines with
carriage return; empty output means 0 rows; paginate large results in Cypher with stable `ORDER BY`,
`SKIP`, `LIMIT`; never pass Cypher as a direct `mgconsole` argument. If missing, locate with
`which mgconsole || find /opt /usr/local -name mgconsole 2>/dev/null | head -1`.

Writeable procedures: after `embeddings.node_sentence(...)`, `node2vec.set_embeddings(...)`, or any
writeable procedure, the same Cypher statement may only finish with `RETURN`. Do not append `SET`,
`MERGE`, `CREATE`, `DELETE`, or `REMOVE`; run follow-up updates in a separate statement.

## Tagged Files

`@` paths hint at scope only. For code work, query Memgraph when structure/relationships are
relevant, then open files for line-level detail.

## Code RAG Vectors (only if RAG has embeddings)

Use `:CodeChunk` only for broad/unfamiliar code discovery. If no compatible
`code_chunk_embedding_v1` index exists or hits are irrelevant, fall back to exact Memgraph queries
or text search and say why.

```cypher
SHOW VECTOR INDEX INFO;

CALL mg.procedures() YIELD name
WITH name
WHERE name CONTAINS 'embeddings' OR name CONTAINS 'vector_search'
RETURN name
ORDER BY name;

CALL embeddings.text(['<hypothesis-specific semantic query>'], {}) YIELD embeddings
WITH embeddings[0] AS queryVector
CALL vector_search.search('code_chunk_embedding_v1', 10, queryVector)
YIELD node AS chunk, similarity
WITH chunk, similarity
WHERE chunk.project = '{{PROJECT_NAME}}'
MATCH (source {project: '{{PROJECT_NAME}}'})-[:HAS_RAG_CHUNK]->(chunk)
RETURN labels(source) AS sourceType, chunk.sourceId AS sourceId,
       chunk.path AS path, chunk.ownerFqn AS ownerFqn, chunk.signature AS signature,
       chunk.text AS text, similarity
ORDER BY similarity DESC;
```

Prefer 5-10 hits. Inspect path/type/signature/text, then query exact `Class`, `Interface`, `Method`,
or `File` nodes. `CodeChunk` text is derived search material: it should include language, path,
symbol name, owner, signature, documentation comments attached to the code symbol (JavaDoc for
Java), and a bounded source excerpt. The ingester creates and refreshes `CodeChunk` rows during
successful re-ingest and watch-mode file updates; agents must not hand-author them.

## Codebase Analysis Queries

Use these only when focused queries are insufficient or broad structure is required.

```cypher
MATCH (p:Package {project: '{{PROJECT_NAME}}'})
RETURN p.language, p.name ORDER BY p.language, p.name;

MATCH (p:Package {project: '{{PROJECT_NAME}}'})-[:CONTAINS]->(c:Class)
WHERE c.isExternal = false
RETURN p.language AS language, p.name AS pkg, c.name AS cls, c.isAbstract, c.isFinal
ORDER BY language, pkg, cls;

MATCH (caller:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
WHERE caller.ownerFqn IS NOT NULL AND callee.ownerFqn IS NOT NULL
  AND caller.ownerFqn <> callee.ownerFqn
WITH caller.ownerDisplayName + ' -> ' + callee.ownerDisplayName AS edge, COUNT(*) AS n
RETURN edge, n ORDER BY n DESC LIMIT 30;

MATCH (c:Class {project: '{{PROJECT_NAME}}'})-[:DECLARES]->(m:Method)
WHERE c.isExternal = false AND m.isSynthetic = false
WITH c.fqn AS cls, COUNT(m) AS n
RETURN cls, n ORDER BY n DESC LIMIT 20;

MATCH (i:Interface {project: '{{PROJECT_NAME}}'})
OPTIONAL MATCH (c:Class {project: '{{PROJECT_NAME}}', isExternal: false})-[:IMPLEMENTS]->(i)
WITH i.fqn AS iface, collect(c.fqn) AS implementors
RETURN iface, implementors ORDER BY iface;
```

## Focused Memgraph Queries

Language/staleness:

```cypher
MATCH (l:Language {project: '{{PROJECT_NAME}}'})-[:CONTAINS]->(c:Code)
RETURN l.name AS languageName, l.graphName AS graphName, c.language AS language
ORDER BY languageName;

MATCH (c:Code {project: '{{PROJECT_NAME}}'})
RETURN c.language AS language, c.lastIngested AS lastIngested
ORDER BY language;
```

Type/member lookup:

```cypher
MATCH (t {project: '{{PROJECT_NAME}}'})
WHERE (t:Class OR t:Interface OR t:Annotation) AND t.name = '<TypeName>'
RETURN labels(t) AS labels, t.fqn AS fqn, t.kind AS kind, t.modulePath AS modulePath,
       t.language AS language, t.framework AS framework
ORDER BY fqn LIMIT 20;

MATCH (c:Class {project: '{{PROJECT_NAME}}', fqn: '<fqn>'})-[:DECLARES]->(m:Method)
RETURN m.signature, m.name, m.startLine, m.endLine, m.returnType, m.visibility, m.isStatic, m.isSynthetic
ORDER BY m.name;

MATCH (c:Class {project: '{{PROJECT_NAME}}', fqn: '<fqn>'})-[:DECLARES]->(f:Field)
RETURN f.fqn, f.name, f.type, f.visibility, f.isStatic, f.kind
ORDER BY f.name;
```

Callers/callees:

```cypher
MATCH (caller:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
WHERE callee.signature CONTAINS '<signature-or-owner-fragment>'
RETURN caller.signature, caller.ownerDisplayName, caller.startLine, caller.endLine
ORDER BY caller.signature LIMIT 100;

MATCH (caller:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(callee:Method {project: '{{PROJECT_NAME}}'})
WHERE caller.signature CONTAINS '<caller-fragment>'
RETURN caller.signature, callee.signature, callee.ownerDisplayName
ORDER BY caller.signature, callee.signature LIMIT 100;
```

Hierarchy before class/interface declaration work:

```cypher
MATCH (c:Class {fqn: '<fqn>', project: '{{PROJECT_NAME}}'})
OPTIONAL MATCH (c)-[:EXTENDS]->(parent:Class {project: '{{PROJECT_NAME}}'})
OPTIONAL MATCH (c)-[:IMPLEMENTS]->(iface:Interface {project: '{{PROJECT_NAME}}'})
OPTIONAL MATCH (child:Class {project: '{{PROJECT_NAME}}'})-[:EXTENDS]->(c)
WITH c.fqn AS classFqn, collect(DISTINCT parent.fqn) AS parents,
     collect(DISTINCT iface.fqn) AS ifaces, collect(DISTINCT child.fqn) AS children
RETURN classFqn, parents, ifaces, children;

MATCH path = (c:Class {fqn: '<fqn>', project: '{{PROJECT_NAME}}'})-[:EXTENDS*]->(a:Class {project: '{{PROJECT_NAME}}'})
RETURN [n IN nodes(path) | n.fqn] AS ancestors;

MATCH (impl:Class {project: '{{PROJECT_NAME}}'})-[:EXTENDS*0..]->(:Class {project: '{{PROJECT_NAME}}'})
      -[:IMPLEMENTS]->(:Interface {project: '{{PROJECT_NAME}}'})-[:EXTENDS*0..]->(i:Interface {fqn: '<iface-fqn>', project: '{{PROJECT_NAME}}'})
RETURN DISTINCT impl.fqn AS implementor ORDER BY implementor;
```

Pagination:

```cypher
MATCH (a:Method {project: '{{PROJECT_NAME}}'})-[:CALLS]->(b:Method {project: '{{PROJECT_NAME}}'})
WHERE a.signature CONTAINS '<fragment>'
WITH a.signature AS caller, b.signature AS callee
RETURN caller, callee
ORDER BY caller, callee
SKIP 0
LIMIT 200;
```

## Schema

Code nodes: `Project(name)`, `Language(project,name,graphName)`, `Code(project,language,lastIngested,languageName)`,
`Package(name,project,language)`, `File(path,project,lastModified,language)`,
`Class/Interface/Annotation(fqn,project,name,visibility,isExternal,language,kind,modulePath,framework)`,
`Method(signature,project,name,ownerFqn,ownerDisplayName,returnType,visibility,isStatic,startLine,endLine,isSynthetic,language,kind)`,
`Field(fqn,project,name,type,visibility,isStatic,language,kind)`, `PendingCall(project,callerSignature,calleeOwnerFqn,calleeName)`,
and `CodeChunk(id,project,sourceLabel,sourceId,language,path,ownerFqn,signature,text,textHash,embedding,embeddingModel,embeddingDimensions)`.

Relationships:

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

## Query Caveats

- Scope node patterns with `project: '{{PROJECT_NAME}}'`. `CALLS` has no relationship-level project, so filter both endpoint `:Method` nodes.
- Keep labels explicit, including `-[:DECLARES]->(m:Method)`. Use `c.isExternal = false`, not `NOT c.isExternal`.
- Aggregate only in `WITH` or `RETURN`; alias aggregate/grouping values before filtering, ordering, or paginating. Variables not named in `WITH` are dropped.
- Put required `MATCH` clauses before `OPTIONAL MATCH`; Memgraph rejects `MATCH` after `OPTIONAL MATCH`.
- Do not use `SHOW PROCEDURES`; use `CALL mg.procedures() YIELD name WITH name WHERE ... RETURN name`.
- Code is grouped through `(:Language)-[:CONTAINS]->(:Code)`. Ctags languages may include `ruby`, `go`, `rust`, `kotlin`, etc.
- Java constructors use `name = '<init>'`; implicit default constructors and Java record accessors can be synthetic. Nested Java FQNs use `$`.
- Java and TypeScript enum members are `:Field {kind: 'enum-member'}`.
- JS/TS modules are synthetic `:Class` owners with `language = 'js'` and `kind = 'module'`; top-level declarations live there. Filter `kind = 'class'` for real classes. TypeScript enums also use `:Class`; interfaces/type aliases use `:Interface`; decorators use `:Annotation`.
- JS/TS local imports, path aliases, re-exports, exported aliases, and owner/name calls may resolve to internal nodes, but unresolved, ambiguous, dynamic, framework, generated, or out-of-source-root calls may remain missing or become `:PendingCall`.
- Angular decorators may set `framework = 'angular'`. Function-valued class fields can appear as callable `:Method` records and also as `:Field` records.
- Python and ctags modules may be synthetic module owners; ctags fallback is inventory-oriented and does not promise calls, imports, inheritance, decorators, or semantic resolution.
- Prefer `Method.ownerFqn` and `ownerDisplayName` for summaries instead of parsing `signature`. Missing edges do not prove no relationship.
