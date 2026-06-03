## Knowledge Graph

Repo is indexed in Memgraph as **`{{PROJECT_NAME}}`**. Use the
`memgraph-ingester-mcp` server for graph work whenever it is available, and pass
`project: "{{PROJECT_NAME}}"` on every tool call unless the client is configured with that default.

### Lookup Order

1. **Memgraph MCP tools** for structure, relationships, symbols, code metadata, and RAG discovery.
2. **Source files** only after graph lookup, for line-level detail.
3. **grep/glob** for strings, templates, comments, and files absent from the graph.
4. **Other tools** only as a last resort.

If the MCP returns no relevant rows, fall back to text search and say why.

### Code Triggers

- **NO DELEGATION:** never delegate architecture analysis, codebase investigations, member/caller lookups, or graph queries to subagents. Use the MCP yourself.
- **Status/pending-work:** call `server_status` or the focused code/memory tool first, then check Git when local changes matter. Use Git alone only when explicitly asked for Git-only status.
- **No ritual analysis:** run `code_orientation` only when broad structure is needed. Prefer focused tools.
- **Audits/hot paths:** for quality, quantity, or hot-path questions, start with `code_quality_stats` and `code_hot_paths(limit=5, include_evidence=false, format="table_json")`; pass `sections=[...]` when only `largestTypes`, `longestMethods`, `fanIn`, or `fanOut` is needed.
- **Reuse:** reuse session-scoped graph results unless source files changed, the user asks for refresh, memory changed, or scope changed.
- **Broad/unfamiliar code:** use `code_search` with 1-3 concise, hypothesis-specific queries, `limit=5`, and `include_text=false`. Treat hits as discovery only; fetch text/source only for selected hits.
- **Known targets:** skip RAG and use exact tools with precise fragments and low limits: `code_lookup_type`, `code_lookup_methods`, `code_callers`, `code_callees`, and `code_hierarchy`.
- **Large results:** keep compact defaults, especially `code_callers` / `code_callees compact=true`; when `meta.hasMore` is true, paginate with `meta.nextSkip`.
- **Row-heavy output:** request `format="table_json"` for `code_search`, `code_lookup_type`, `code_lookup_methods`, `code_callers`, `code_callees`, `code_hot_paths`, `code_quality_stats`, and `raw_read_cypher`; read results as `cols` plus `rows`.
- **Type lookup compaction:** for `code_lookup_type`, prefer `compact=true` and `member_summary=false` unless visibility/framework/member counts are needed; keep `include_members=false` unless member rows are needed.
- **Before source-code changes:** use `code_search` first when broad/unfamiliar; use the smallest exact tool set when known.
- **Class/interface declaration changes:** call `code_hierarchy` before changing inheritance, implemented interfaces, constructors, or overridden APIs.
- **Method bodies:** use `code_lookup_methods(compact=true)` to get `startLine`/`endLine`, then read only that range.
- **After edits:** if source changed and you need relationships again, re-query the MCP because live ingestion may have refreshed the graph.

### Code MCP Tools

- `server_status`: graph inventory, memory counts, vector indexes.
- `code_quality_stats`: compact graph-wide quality and quantity metrics.
- `code_hot_paths`: compact hot-path candidates by type size, method size, fan-in, and fan-out; start with `include_evidence=false`.
- `code_orientation`: compact language/package/type/call overview; pass `sections` and small `limit` values.
- `code_search`: CodeChunk RAG discovery. Text is omitted unless `include_text=true`.
- `code_lookup_type`: class/interface/annotation details; members are omitted unless `include_members=true`; set `member_summary=false` when counts are not needed.
- `code_lookup_methods`: exact method records and source ranges; use `compact=true` when only ranges are needed.
- `code_callers` / `code_callees`: compact, paginated call graph lookup by default.
- `code_hierarchy`: parents, implemented interfaces, children, ancestors, and interface implementors.
- `raw_read_cypher`: read-only, project-scoped Cypher for rare gaps only.

If `memgraph-ingester-mcp` tool is not available switch to regular tools and **mention it**.

### Tagged Files

`@` paths hint at scope only. For code work, query the MCP when structure or relationships are
relevant, then open files for exact line-level detail.

### Query Caveats

- Memgraph reverse() is string-only; don’t use it for lists.
- Missing `CALLS` edges do not prove no relationship; dynamic or unresolved calls may be absent.
- Java constructors use `name = '<init>'`; implicit default constructors and record accessors can be synthetic.
- JS/TS modules can be synthetic `:Class` owners; filter `kind = 'class'` for real classes when needed.
- Python and ctags fallback data may be inventory-oriented and may not include calls, imports, inheritance, or decorators.
- Prefer `ownerFqn` and `ownerDisplayName` from MCP results instead of parsing signatures.
