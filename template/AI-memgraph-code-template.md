## Knowledge Graph

Repo is indexed in Memgraph as **`{{PROJECT_NAME}}`**. Use `memgraph-ingester-mcp` for
code structure, relationships, hot-path stats, and RAG discovery. Pass
`project: "{{PROJECT_NAME}}"` unless the client already has that default.

### Lookup Order

1. Start with focused MCP tools: `server_status`, `code_quality_stats`, `code_hot_paths`,
   or `code_orientation` with only needed `sections`.
2. Use `code_search` only for broad discovery; keep `limit` small and leave `include_text=false`
   unless excerpts are truly needed.
3. Use exact tools for known targets: `code_lookup_type`, `code_lookup_methods`,
   `code_callers`, `code_callees`, `code_hierarchy`.
4. Read source files only for exact line-level detail after MCP lookup.
5. Use grep/glob or `raw_read_cypher` only for gaps; say why when falling back.

### Code Rules

- **NO DELEGATION:** run graph/code investigations yourself; do not delegate MCP work.
- Reuse graph results in-session unless source changed, scope changed, or refresh is requested.
- For `code_lookup_type`, keep `include_members=false`; request `member_summary` or a small
  `member_limit` only when needed.
- For callers/callees, prefer compact defaults and paginate with `nextSkip`.
- Before inheritance/API declaration changes, call `code_hierarchy`.
- For method bodies, call `code_lookup_methods`, then read only `startLine`..`endLine`.
- After edits, re-query MCP before making relationship claims.

### Caveats

- Missing `CALLS` edges do not prove no relationship; dynamic calls may be unresolved.
- Java constructors use `name = '<init>'`; synthetic constructors/accessors may appear.
- JS/TS modules can be synthetic `:Class` owners; filter `kind = 'class'` for real classes.
- Prefer MCP `ownerFqn` and `ownerDisplayName` over parsing signatures.
