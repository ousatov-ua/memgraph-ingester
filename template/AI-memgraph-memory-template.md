## Memories

This optional section enables durable Memgraph Memory usage for **`{{PROJECT_NAME}}`**. Every query
MUST include `project: '{{PROJECT_NAME}}'`.

### Memory Triggers

- **NO DELEGATION:** Never delegate memory state queries or updates to subagents. You MUST use Memgraph.
- **Status/pending-work requests:** run Orientation queries first, then check Git if local changes are relevant. Never answer from Git alone unless the user explicitly asks for Git-only status.
- **Orientation reuse:** Orientation queries are session-scoped. If they were already run for `{{PROJECT_NAME}}` in this assistant session, reuse those results for follow-up work and skip rerunning them unless memory was changed, the user asks for a refresh, or the task scope is unrelated.
- **RAG-first memory discovery:** use Memory RAG only to find relevant project knowledge for broad history/context prompts: prior/similar work, task history, status/pending work, "when was this changed", or unfamiliar-subsystem context. For known Memory ids, exact status/type lists, lifecycle updates, task close, `CodeRef` follow-up, and scoped Orientation queries, skip RAG and use exact Memory queries. RAG hits are discovery only: fetch exact Memory nodes and linked `CodeRef` targets before claims or edits. If no compatible `memory_chunk_embedding_v1` index exists or hits are not relevant, fall back to scoped exact Orientation queries and state why.
- **RAG as an intelligence boost:** for broad or ambiguous work, proactively turn the prompt, errors, symbols, and verified findings into small semantic queries. Use RAG to surface non-obvious context, similar work, and prior decisions before broad exact scans; then verify every useful hit with exact Memory/CodeRef/source lookups.
- **Self-formulated RAG bounds:** agents may write their own semantic queries from the prompt, observed symbols/errors, and current hypothesis. Each query must aim at one next exact Memory/CodeRef lookup or source read. Count a search as useful only if it names a concrete Memory id, CodeRef, or code target to verify. After two unhelpful RAG searches for the same question, stop RAG and switch to exact Memory orientation or text search. The two-search limit resets only after exact verification changes the question or reveals a new concrete clue; do not chain RAG searches from RAG hits alone.
- **Memory investigation budget:** do not run Memory RAG or Orientation for ordinary code implementation/debugging unless the user asks for prior history, status, pending work, existing Memory ids, or unfamiliar-subsystem context. Initial Memory RAG is index-only: return ids, titles, statuses, labels, and similarity, not `chunk.text` or full body fields. Start with at most 5 hits, then fetch exact Memory records only for selected IDs that are relevant to the current task. Create/update the active Task when required, but do not enumerate unrelated Memory nodes just to begin code work.
- **Multi-step work tracking:** for multi-step implementation, debugging, refactoring, documentation, dependency, test, or coverage work, create/update a `Task` as `doing` before edits, even if you expect to finish in the same response.
- **Task close:** set any task you created or updated to `done`, `blocked`, or `cancelled` before final response and verify it. Also save durable findings/decisions when useful.
- **Memory lifecycle changes:** immediately update Task/Risk/Question/Decision/ADR/Idea status in Memgraph before proceeding.
- **Session Memory embedding refresh:** every time you create or materially update a Memory node in the current session, create/update its `MemoryChunk`, clear stale embedding properties when the text changes, and then create the embedding for that MemoryChunk with Memgraph's `embeddings.node_sentence()` batch flow. Do not calculate embedding vectors outside Memgraph. Because `embeddings.node_sentence()` is writeable, its Cypher statement must end with `RETURN`; stamp embedding metadata in a separate statement.
- **Code-related memory:** when creating Task/Decision/Finding/Rule/ADR/Risk/Idea nodes related to code, create at least one `CodeRef` and link `(:Decision|:ADR|:Rule|:Context|:Finding|:Task|:Risk|:Question|:Idea)-[:REFERS_TO]->(:CodeRef)-[:RESOLVES_TO]->(:Code|:Package|:File|:Class|:Interface|:Annotation|:Method|:Field)`.

### Memory RAG Vectors (only if RAG has embeddings)

Use `:MemoryChunk` as the mandatory semantic discovery layer only to find relevant project
knowledge for broad history/context prompts: prior/similar work, task history, status/pending work,
"when was this changed", or unfamiliar-subsystem context whenever a compatible embedding index
exists. Known Memory ids, lifecycle updates, `CodeRef` follow-ups, and scoped Orientation queries use
exact Memory queries directly. The source of truth remains the canonical Memory node and its
status/severity fields.

Before vector search, verify a matching vector index exists:

```cypher
SHOW VECTOR INDEX INFO;
```

If procedure discovery is needed, use `mg.procedures()` and filter after `WITH`; do not use
`SHOW PROCEDURES`, and do not place `WHERE` immediately after `YIELD`:

```cypher
CALL mg.procedures() YIELD name
WITH name
WHERE name CONTAINS 'embeddings' OR name CONTAINS 'vector_search'
RETURN name
ORDER BY name;
```

Search semantically similar memory chunks with a query vector created by the same embedding model
and dimension as the stored chunks:

```cypher
CALL embeddings.text(['<task-specific semantic query from the user request>'], {}) YIELD embeddings
WITH embeddings[0] AS queryVector
CALL vector_search.search('memory_chunk_embedding_v1', 5, queryVector)
YIELD node AS chunk, similarity
WITH chunk, similarity
WHERE chunk.project = '{{PROJECT_NAME}}'
MATCH (memory {project: '{{PROJECT_NAME}}'})-[:HAS_RAG_CHUNK]->(chunk)
RETURN labels(memory) AS type, memory.id AS id, memory.title AS title,
       memory.status AS status, chunk.sourceLabel AS sourceLabel,
       chunk.sourceId AS sourceId, similarity
ORDER BY similarity DESC;
```

Use the user's wording plus likely domain terms in the semantic query, for example:
"JS/TS parser synthetic constructors constructor declarations previous analyzer fixes". Prefer the
top relevant hits, then fetch exact Memory records by ID only for selected hits and follow `CodeRef`
links when code context matters.

After a RAG hit, follow `CodeRef` links when code context matters:

```cypher
MATCH (memory {project: '{{PROJECT_NAME}}', id: '<memory-id>'})
OPTIONAL MATCH (memory)-[:REFERS_TO]->(ref:CodeRef)-[:RESOLVES_TO]->(target)
RETURN labels(memory) AS type, memory.id AS id,
       ref.targetType AS targetType, ref.key AS key, labels(target) AS targetLabels;
```

### Orientation

Run at task start when required. 

#### If Memgraph RAG has embeddings

For broad project-knowledge prompts, search semantically similar memory chunks first for relevant
Context, Findings, Rules, Tasks, Questions, Risks, Ideas, ADRs, or Decisions. Keep this search
index-only; do not return `chunk.text` or full Memory body fields during discovery. Then run exact
Memory queries for the selected IDs only.
Finally, fetch linked `CodeRef` targets and use code-graph/source queries when code context matters.
For known Memory ids, lifecycle updates, `CodeRef` follow-ups, or scoped Orientation queries, use
exact Memory queries directly. Do not answer broad project-knowledge prompts from an exact
Context/Task list alone unless Memory RAG is absent or produced no relevant rows; state that fallback
explicitly.

#### If Memgraph RAG has no embeddings

Run at task start when required.

**BLOCKING** Don't just fetch all. Use RAG first if available. Fetch only the relevant subset of Memory nodes for the current task.
Use the following queries as templates, adapting filters and ordering to the task scope and needs. 
Empty results are valid.

```cypher
MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_RULE]->(r:Rule)
RETURN r.id, r.severity, r.description ORDER BY r.severity;

MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_FINDING]->(f:Finding)
WHERE f.status = 'open'
RETURN f.id, f.type, f.summary;

MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_CONTEXT]->(c:Context)
RETURN c.id, c.title, c.topic, c.source
ORDER BY c.topic, c.id;

MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_TASK]->(t:Task)
WHERE t.status IN ['todo', 'doing', 'blocked']
RETURN t.id, t.title, t.status, t.priority
ORDER BY t.priority, t.status;

MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_QUESTION]->(q:Question)
WHERE q.status = 'open'
RETURN q.id, q.title;

MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_RISK]->(r:Risk)
WHERE r.status = 'open'
RETURN r.id, r.title, r.severity;
```

Context orientation is index-first: fetch `content` only for relevant Context IDs, topics, or
keywords. Fetch all Context content only for broad or ambiguous memory work.

```cypher
MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_CONTEXT]->(c:Context)
WHERE c.id IN ['CTX-<id>'] OR c.topic IN ['<topic>']
RETURN c.id, c.title, c.topic, c.content, c.source
ORDER BY c.id;
```

## Memory Schema

**Strict:** no extra properties.

Use `datetime()` for all property `createdAt` and `updatedAt` values; do not use `localDateTime()` for memory timestamps.

| Label       | Key props                      | Additional properties                                                                        |
|-------------|--------------------------------|----------------------------------------------------------------------------------------------|
| `:Memory`   | `project`                      | -                                                                                            |
| `:Decision` | `id`, `project`                | `title`, `topic`, `status`, `rationale`, `consequences`, `createdAt`, `updatedAt`            |
| `:ADR`      | `id`, `project`                | `number`, `title`, `status`, `context`, `decision`, `consequences`, `createdAt`, `updatedAt` |
| `:Rule`     | `id`, `project`                | `title`, `topic`, `severity`, `description`, `createdAt`, `updatedAt`                        |
| `:Context`  | `id`, `project`                | `title`, `topic`, `content`, `source`, `createdAt`, `updatedAt`                              |
| `:Finding`  | `id`, `project`                | `title`, `topic`, `type`, `status`, `summary`, `evidence`, `createdAt`, `updatedAt`          |
| `:Task`     | `id`, `project`                | `title`, `status`, `priority`, `description`, `createdAt`, `updatedAt`                       |
| `:Risk`     | `id`, `project`                | `title`, `topic`, `severity`, `status`, `mitigation`, `createdAt`, `updatedAt`               |
| `:Question` | `id`, `project`                | `title`, `status`, `answer`, `createdAt`, `updatedAt`                                        |
| `:Idea`     | `id`, `project`                | `title`, `topic`, `status`, `notes`, `createdAt`, `updatedAt`                                |
| `:CodeRef`  | `project`, `targetType`, `key` | -                                                                                            |
| `:MemoryChunk` | `id`, `project`             | `sourceLabel`, `sourceId`, `text`, `textHash`, `embedding`, `embeddingModel`, `embeddingDimensions`, `createdAt`, `updatedAt` |

Controlled values:
- Decision/ADR `status`: `proposed`, `accepted`, `rejected`, `superseded`; ADR also `draft`.
- Rule `severity`: `hard`, `soft`, `recommendation`.
- Finding `type`: `bug`, `perf`, `constraint`, `security`; `status`: `open`, `resolved`, `obsolete`.
- Task `status`: `todo`, `doing`, `done`, `blocked`, `cancelled`; `priority`: `0` critical, `1` high, `2` medium, `3` low, `4` none.
- Risk `severity`: `low`, `medium`, `high`, `critical`; `status`: `open`, `mitigated`, `accepted`, `obsolete`.
- Question `status`: `open`, `answered`, `obsolete`.
- Idea `status`: `proposed`, `accepted`, `rejected`, `obsolete`.

ID prefixes: `DEC-`, `ADR-<n>-`, `RULE-`, `FIND-`, `TASK-`, `RISK-`, `CTX-`, `Q-`, `IDEA-` + `<topic>-<name>`.

Memory links:

```text
(:Project)-[:HAS_MEMORY]->(:Memory)-[:HAS_*]->(:Decision|:ADR|:Rule|:Context|:Finding|:Task|:Risk|:Question|:Idea)
(:Decision|...)-[:REFERS_TO]->(:CodeRef)-[:RESOLVES_TO]->(:Code|:Package|:File|:Class|:Interface|:Annotation|:Method|:Field)
(:Decision|...)-[:HAS_RAG_CHUNK]->(:MemoryChunk)
```

`CodeRef.key`: reference language key for `Code` (`java`, `js`, or `python`), language-prefixed package name for `Package` (`java:<package>`, `js:<package>`, or `python:<package>`), path for `File`, FQN for types/fields, signature for `Method`.

`MemoryChunk` is derived RAG data. Its `text` should include the memory type, title, topic,
status/severity/type/priority, body fields, and linked `CodeRef` summaries. Do not use chunks as
authoritative state; inspect the source Memory node before acting.

## Memory Policy

Memory is not a changelog. Store only information useful for future decisions, investigations, or implementation work.
Do not create memory nodes just because files changed; routine edits belong in Git diff, tests, and final response.
Use concise Markdown in free-text memory fields when it improves scanning, such as `content`, `summary`, `evidence`, `description`, `rationale`, `consequences`, `mitigation`, `answer`, or `notes`. Keep it short and summarizable.

A `Task` is for durable work tracking, not every assistant action.
Create or update a `Task` for explicit tracking/follow-up requests, unfinished or blocked work, continuation of an existing Task, or multi-step implementation/debugging/refactoring/documentation/dependency/test/coverage work.
Create the `Task` even when the work is completed in the same response.
Do not create a `Task` for one-off read-only requests, status checks, simple lookups, or pure verification that does not modify files.
When unsure: if multi-step file edits are involved, create a `Task`; otherwise prefer not creating one.

Create/update:
- `Decision` for design or implementation choices.
- `ADR` for architecture direction.
- `Rule` for future constraints.
- `Finding` for bugs, performance issues, wrong assumptions, or codebase limitations.
- `Context` for durable subsystem behavior, operational caveats, recurring failure modes, or reusable investigation summaries.
- `Task` for durable tracked work, active implementation/debugging/documentation work, or unfinished follow-up.
- `Question` for open questions.
- `Risk` for new or discovered risks.

For `Context`, prefer updating an existing summary such as `CTX-<topic>-summary`, `CTX-<subsystem>-constraints`, or `CTX-<workflow>-notes`.
Keep content concise and current. Do not use Context for routine file changes, test commands, or information better captured as Decision/ADR/Finding/Task/Risk/Question/Rule.

### Task Lifecycle

```cypher
MATCH (t:Task {id: 'TASK-<id>', project: '{{PROJECT_NAME}}'})
SET t.status = 'doing', t.updatedAt = datetime();

MATCH (t:Task {id: 'TASK-<id>', project: '{{PROJECT_NAME}}'})
SET t.status = 'done', t.updatedAt = datetime();
```

### Saving Memory

#### Interactive input limits

With interactive `mgconsole`, keep memory writes short. Do not paste long one-line statements with
large string literals; store concise summaries and split lifecycle/content/link/verification into
separate statements. If the console waits after a paste, cancel/restart and replay short statements.

Create/update the memory node first:

```cypher
MERGE (m:Memory {project: '{{PROJECT_NAME}}'})
MERGE (d:Decision {id: 'DEC-<topic>-<name>', project: '{{PROJECT_NAME}}'})
SET d.title = '<title>', d.topic = '<topic>', d.status = 'accepted',
    d.rationale = '<rationale>', d.consequences = '<consequences>',
    d.createdAt = coalesce(d.createdAt, datetime()), d.updatedAt = datetime()
MERGE (m)-[:HAS_DECISION]->(d);
```

Link to code in a separate query. Use `MATCH` for existing code nodes; do not `MERGE` them inline because unique constraints can fail.

```cypher
MATCH (d:Decision {id: 'DEC-<topic>-<name>', project: '{{PROJECT_NAME}}'})
MATCH (c:Class {fqn: '<fqn>', project: '{{PROJECT_NAME}}'})
MERGE (ref:CodeRef {project: '{{PROJECT_NAME}}', targetType: 'Class', key: '<fqn>'})
MERGE (d)-[:REFERS_TO]->(ref)
MERGE (ref)-[:RESOLVES_TO]->(c);
```

For `targetType: 'Code'`, use `key: 'java'`, `key: 'js'`, or `key: 'python'`. For `targetType: 'Package'`, use `key: 'java:<package>'`, `key: 'js:<package>'`, or `key: 'python:<package>'`.

When creating or materially updating a Memory node, also create or refresh one derived
`MemoryChunk` for semantic RAG discovery. Store the chunk text and a stable hash of that exact text,
clear old embedding properties when the text changes, then create the embedding with Memgraph. Do
not calculate embedding vectors outside Memgraph. Use the source Memory node as the authority. This
agent workflow is only for MemoryChunks created or updated during the current session.
Compute `textHash` outside Cypher with any SHA-256 tool/library as the hex digest of the exact UTF-8
`chunk.text`; when using MCP, pass it as a parameter. Do not call `sha256()` in Memgraph.
When using Node.js snippets, avoid top-level `createHash` declarations; keep imports and helpers
scoped to the snippet so repeated calls do not redeclare persistent REPL bindings.

```cypher
MATCH (d:Decision {id: 'DEC-<topic>-<name>', project: '{{PROJECT_NAME}}'})
MERGE (chunk:MemoryChunk {id: 'MCH-DEC-<topic>-<name>', project: '{{PROJECT_NAME}}'})
SET chunk.sourceLabel = 'Decision',
    chunk.sourceId = d.id,
    chunk.text = '<type/title/topic/status/body/code-ref-summary text>',
    chunk.textHash = $textHash,
    chunk.createdAt = coalesce(chunk.createdAt, datetime()),
    chunk.updatedAt = datetime()
REMOVE chunk.embedding, chunk.embeddingModel, chunk.embeddingDimensions
MERGE (d)-[:HAS_RAG_CHUNK]->(chunk);
```

Ensure the MemoryChunk vector index exists before embedding refresh. Run `SHOW VECTOR INDEX INFO;`
first and create the index only when `memory_chunk_embedding_v1` is absent:

```cypher
CREATE VECTOR INDEX memory_chunk_embedding_v1 ON :MemoryChunk(embedding)
WITH CONFIG $config;
```

Use the same batch shape as `refresh-code-chunk-embedding-batch.cypher`, adapted for
`:MemoryChunk`, after every MemoryChunk create/update in the current session. Set `$ids` to the
MemoryChunk ids created or updated by the session. The query below embeds only those chunks when
their embedding is missing or their recorded model/dimension no longer matches the configured
embedding model. It intentionally ends with `RETURN` immediately after `embeddings.node_sentence()`;
do not append `SET`, `MERGE`, `CREATE`, `DELETE`, or `REMOVE` to this statement:

```cypher
MATCH (chunk:MemoryChunk {project: '{{PROJECT_NAME}}'})
WHERE chunk.id IN $ids
  AND chunk.text IS NOT NULL
  AND (chunk.embedding IS NULL
    OR chunk.embeddingModel IS NULL
    OR chunk.embeddingModel <> $modelName
    OR chunk.embeddingDimensions IS NULL
    OR chunk.embeddingDimensions <> $dimension)
WITH chunk
ORDER BY chunk.id
WITH collect(chunk) AS chunks
WITH chunks, [chunk IN chunks | chunk.id] AS embeddedIds
CALL embeddings.node_sentence(chunks, $config)
YIELD success, dimension
RETURN success AS success, dimension AS dimension, embeddedIds AS ids;
```

When the batch returns `success = true`, stamp the MemoryChunk embedding metadata for the returned
ids in a separate statement, mirroring `update-code-chunk-embedding-metadata.cypher`:

```cypher
MATCH (chunk:MemoryChunk {project: '{{PROJECT_NAME}}'})
WHERE chunk.id IN $ids
SET chunk.embeddingModel = $modelName,
    chunk.embeddingDimensions = $dimension,
    chunk.updatedAt = datetime();
```

Do not run a whole-project MemoryChunk embedding backfill from these agent instructions. Missing
Memory embeddings after code re-ingest are handled by the re-ingest flow only when ingestion is run
with `--with-memories`, using the same missing-embedding batch pattern as CodeChunks.

Verify recent memory and its code link before the final response. Adapt `HAS_DECISION`,
`:Decision`, and `d` to the memory type just created:

```cypher
MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_DECISION]->(d:Decision)
WHERE d.updatedAt >= datetime() - duration('PT5M')
RETURN d.id AS id, labels(d) AS type;

MATCH (d:Decision {project: '{{PROJECT_NAME}}'})-[:REFERS_TO]->(ref:CodeRef)-[:RESOLVES_TO]->(target)
WHERE d.updatedAt >= datetime() - duration('PT5M')
RETURN d.id AS id, ref.targetType AS targetType, ref.key AS key, labels(target) AS targetLabels;
```

> Memory is not logs. Store only what improves future decisions.
