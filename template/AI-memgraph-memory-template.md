## Memories

This optional section enables durable Memgraph Memory usage for **`{{PROJECT_NAME}}`**. Use the
`memgraph-ingester-mcp` server for memory discovery and lifecycle changes whenever it is available,
and pass `project: "{{PROJECT_NAME}}"` on every tool call unless the client is configured with that
default.

### Memory Triggers

- **NO DELEGATION:** never delegate memory state queries, lifecycle changes, or memory/code-ref lookups to subagents. Use the MCP yourself.
- **Status/pending-work:** call `memory_orientation` first, then Git if local changes matter. Use Git alone only when explicitly asked for Git-only status.
- **Orientation reuse:** reuse session-scoped orientation unless memory changed, the user asks for refresh, or scope changed.
- **Broad history/context:** use `memory_search` with 1-3 concise, hypothesis-specific queries. Treat hits as discovery only, then call `memory_get` for relevant IDs before acting.
- **Known IDs and lifecycle changes:** skip RAG and use exact tools such as `memory_get`, `memory_schema`, `memory_update_status`, `delete_memory`, `memory_link_code_ref`, `memory_refresh_chunk`, or `memory_refresh_embeddings`.
- **Schema before writes:** call `memory_schema` before `memory_upsert` or `memory_update_status` when the memory type fields, controlled values, or CodeRef target types are not already known in this session. Use the returned schema exactly; do not guess fields such as `status` or `summary` for memory types that do not list them.
- **Memory deletion:** use `delete_memory` only when a Memory is wrong, duplicated, or should no longer exist. Pass the exact `memory_id` and `project`; verify `deleted: true`. Prefer status updates for completed, cancelled, or merely stale work.
- **Task tracking:** for multi-step implementation, debugging, refactoring, documentation, dependency, test, or coverage work, create/update a `Task` as `doing` before edits. Close any Task you touched as `done`, `blocked`, or `cancelled` before final response and verify it.
- **Code-related memory:** include a `code_ref` in `memory_upsert` when possible, or call `memory_link_code_ref` after the target exists.
- **Session Memory RAG:** `memory_upsert` refreshes derived chunks by default. Use `memory_refresh_chunk` or `memory_refresh_embeddings` after material updates made outside that tool.

### Memory MCP Tools

- `memory_orientation`: rules plus open findings, tasks, questions, and risks.
- `memory_search`: MemoryChunk RAG discovery with index-only hit metadata.
- `memory_get`: canonical Memory node plus resolved CodeRefs.
- `memory_upsert`: create/update `Decision`, `ADR`, `Rule`, `Context`, `Finding`, `Task`, `Risk`, `Question`, or `Idea`.
- `memory_update_status`: lifecycle status updates.
- `delete_memory`: delete one Memory node plus its derived chunk and orphan CodeRefs.
- `memory_link_code_ref`: link a Memory node to `Code`, `Package`, `File`, `Class`, `Interface`, `Annotation`, `Method`, or `Field`.
- `memory_refresh_chunk`: rebuild one derived `MemoryChunk`.
- `memory_refresh_embeddings`: refresh selected MemoryChunk embeddings and stamp metadata.

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

Controlled values are enforced by the MCP. Keep IDs stable and descriptive, for example
`TASK-<topic>-<name>`, `DEC-<topic>-<name>`, `FIND-<topic>-<name>`, and `CTX-<topic>-<name>`.
