## Memories

This optional section enables durable Memgraph Memory usage for **`{{PROJECT_NAME}}`** without an
MCP server. Use the **`mgtools`** CLI from the [`memgraph-ingester-tool`](https://pypi.org/project/memgraph-ingester-tool/)
PyPI package for memory discovery and lifecycle changes, and pass `--project "{{PROJECT_NAME}}"`
on every call unless `MEMGRAPH_TOOLS_PROJECT` is exported.

If the venv from the Knowledge Graph section is not present yet, install it once:

```bash
python3 -m venv .venv-mgtools
.venv-mgtools/bin/pip install --quiet --upgrade memgraph-ingester-tool
```

Always call the venv binary by path (`.venv-mgtools/bin/mgtools …`); do not rely on `PATH` or
shell state persisting between tool invocations. Write commands (`memory_upsert`,
`memory_update_status`, `delete_memory`, `memory_link_code_ref`, `memory_refresh_chunk`,
`memory_refresh_embeddings`) are rejected when `MEMGRAPH_TOOLS_READ_ONLY=true`.

### Memory Triggers

- **Status/pending-work:** call `memory_orientation --compact` first, then Git if local changes matter. Use Git alone only when explicitly asked for Git-only status.
- **Orientation reuse:** reuse session-scoped orientation unless memory changed, the user asks for refresh, or scope changed.
- **Broad history/context:** use `memory_search` with 1-3 concise, hypothesis-specific queries and `--limit 5`. Treat hits as discovery only, then call `memory_get` only for selected IDs before acting.
- **Known IDs and lifecycle changes:** skip RAG and use exact commands such as `memory_get`, `memory_schema`, `memory_update_status`, `delete_memory`, `memory_link_code_ref`, `memory_refresh_chunk`, or `memory_refresh_embeddings`.
- **Response conventions:** responses are compact JSON on stdout — **absent keys = null or empty**; never treat a missing attribute as an error. `deleted: true` is only present on successful deletion. Errors print `{"error": "..."}` to stderr with exit code 1.
- **Schema before writes:** call `memory_schema` before `memory_upsert` or `memory_update_status` when fields, controlled values, or CodeRef targets are not already known. Scope it with `--memory-type` when known and reuse the result; do not guess fields such as `status` or `summary`.
- **Memory deletion:** use `delete_memory` only when a Memory is wrong, duplicated, or should no longer exist. Pass the exact memory id and `--project`; verify `deleted: true`. Prefer status updates for completed, cancelled, or merely stale work.
- **Task tracking:** for multi-step implementation, debugging, refactoring, documentation, dependency, test, or coverage work, create/update a `Task` as `doing` before edits; use `--no-embed` for temporary in-flight updates. Close any Task you touched as `done`, `blocked`, or `cancelled` before final response and verify it.
- **Code-related memory:** include `--code-ref` in `memory_upsert` when possible, or call `memory_link_code_ref` after the target exists.
- **Session Memory RAG:** `memory_upsert` refreshes derived chunks by default. Use `memory_refresh_chunk` or `memory_refresh_embeddings` after material updates made outside that command.

### Memory Commands

All commands accept `--project NAME`. Fields and code refs are passed as JSON strings —
single-quote them in the shell.

- `mgtools memory_orientation [--compact]`: rules plus open findings, tasks, questions, and risks; use `--compact` unless body details are needed.
- `mgtools memory_schema [--memory-type TYPE]`: allowed memory types, upsert fields, controlled values, and CodeRef targets.
- `mgtools memory_search "QUERY" [--limit 5]`: MemoryChunk RAG discovery with index-only hit metadata.
- `mgtools memory_get MEMORY_ID`: canonical Memory node plus resolved CodeRefs.
- `mgtools memory_upsert TYPE MEMORY_ID 'FIELDS_JSON' [--code-ref 'JSON'] [--no-refresh-chunk] [--no-embed]`: create/update `Decision`, `ADR`, `Rule`, `Context`, `Finding`, `Task`, `Risk`, `Question`, or `Idea`.
- `mgtools memory_update_status TYPE MEMORY_ID STATUS [--no-refresh-chunk] [--no-embed]`: lifecycle status updates.
- `mgtools delete_memory MEMORY_ID`: delete one Memory node plus its derived chunk and orphan CodeRefs.
- `mgtools memory_link_code_ref TYPE MEMORY_ID TARGET_TYPE KEY [--no-refresh-chunk] [--no-embed]`: link a Memory node to `Code`, `Package`, `File`, `Class`, `Interface`, `Annotation`, `Method`, or `Field`.
- `mgtools memory_refresh_chunk TYPE MEMORY_ID [--no-embed]`: rebuild one derived `MemoryChunk`.
- `mgtools memory_refresh_embeddings CHUNK_ID [CHUNK_ID…]`: refresh selected MemoryChunk embeddings and stamp metadata.

Examples:

```bash
M=.venv-mgtools/bin/mgtools
"$M" memory_orientation --compact --project "{{PROJECT_NAME}}"
"$M" memory_upsert Task TASK-ingestion-retry-logic \
  '{"summary": "Add retry logic to ingestion writer", "status": "doing"}' \
  --code-ref '{"target_type": "Class", "key": "io.example.GraphWriter"}' \
  --project "{{PROJECT_NAME}}"
"$M" memory_update_status Task TASK-ingestion-retry-logic done --project "{{PROJECT_NAME}}"
```

### Memory Policy

Memory is not a changelog. Store only information useful for future decisions, investigations, or
implementation.

- Use `Decision` for design/implementation choices.
- Use `ADR` for architecture direction.
- Use `Rule` for future constraints.
- Use `Finding` for bugs, performance issues, wrong assumptions, or limitations.
- Use `Context` for durable subsystem behavior, operational caveats, recurring failure modes, or reusable investigation summaries.
- Use `Task` for tracked active work or unfinished follow-up.
- Use `Question` for open questions.
- Use `Risk` for new/discovered risks.
- Use `Idea` for possible future improvements.

Controlled values are enforced by the tool. Keep IDs stable and descriptive, for example
`TASK-<topic>-<name>`, `DEC-<topic>-<name>`, `FIND-<topic>-<name>`, and `CTX-<topic>-<name>`.
