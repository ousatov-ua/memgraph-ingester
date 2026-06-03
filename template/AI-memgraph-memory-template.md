## Memories

Memgraph Memory is enabled for **`{{PROJECT_NAME}}`**. Use `memgraph-ingester-mcp` for memory
orientation, search, reads, writes, and CodeRef links. Pass `project: "{{PROJECT_NAME}}"` unless
the client already has that default.

### Memory Rules

- **NO DELEGATION:** run memory queries and lifecycle changes yourself.
- For status/pending work, call `memory_orientation` first, then Git if local changes matter.
- For broad context, use `memory_search` with a small `limit`; call `memory_get` only for relevant IDs.
- For known IDs or lifecycle changes, use exact tools: `memory_get`, `memory_update_status`,
  `delete_memory`, `memory_link_code_ref`, `memory_refresh_chunk`, `memory_refresh_embeddings`.
- Call `memory_schema` before writes when fields or controlled values are not already known.
- For multi-step implementation/debug/refactor/doc/test work, create or update a `Task` as `doing`
  before edits; close touched tasks as `done`, `blocked`, or `cancelled` before final response.
- Prefer status updates over deletion; delete only wrong, duplicated, or unwanted memories.
- Include `code_ref` when possible so future sessions can navigate from memory to code.

### Memory Policy

Memory is not a changelog. Store only durable decisions, rules, context, findings, tasks, risks,
questions, ideas, or ADRs that will help future work. Keep IDs stable and descriptive, for example
`TASK-<topic>-<name>`, `DEC-<topic>-<name>`, `FIND-<topic>-<name>`, and `CTX-<topic>-<name>`.
