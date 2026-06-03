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
- **Audits/hot paths:** for hot-path/performance questions, start with `code_hot_paths` only; defaults are compact (`limit=5`, sections `["fanIn","longestMethods"]`, `include_evidence=false`). Add `code_quality_stats` only when graph-wide baselines are needed, and pass `include_evidence=true` when source location is needed — it returns `path/startLine/endLine` directly, so a follow-up `code_lookup_methods` is not needed.
- **Reuse:** reuse session-scoped graph results unless source files changed, the user asks for refresh, memory changed, or scope changed.
- **Broad/unfamiliar code:** use 1 concise `code_search` query first; use a second query only if the first misses the concept. Defaults are compact (`limit=5`, `include_text=false`). Treat hits as discovery only; fetch text/source only for selected hits.
- **Known targets:** skip RAG and use exact tools with precise fragments and compact defaults: `code_lookup_type`, `code_lookup_methods`, `code_callers`, `code_callees`, and `code_hierarchy`.
- **Large results:** keep compact first pages; when `meta.hasMore` is true, paginate with `meta.nextSkip` instead of raising limits preemptively.
- **Row-heavy output:** all row-heavy tools (`code_search`, `code_lookup_type`, `code_lookup_methods`, `code_callers`, `code_callees`, `code_hot_paths`, `code_quality_stats`, `raw_read_cypher`) default to `format="table_json"`; read results as `cols` plus `rows`. Pass `format="json"` only when raw object format is required.
- **Type lookup compaction:** `code_lookup_type` defaults to `compact=true` and `member_summary=false`; pass `compact=false` or `member_summary=true` only when visibility, framework, or member counts are needed. Keep `include_members=false` unless member rows are needed.
- **Before source-code changes:** use `code_search` first when broad/unfamiliar; use the smallest exact tool set when known.
- **Class/interface declaration changes:** call `code_hierarchy` before changing inheritance, implemented interfaces, constructors, or overridden APIs.
- **Method bodies:** use `code_lookup_methods` to get `startLine`/`endLine`, then read only that range.
- **After edits:** if source changed and you need relationships again, re-query the MCP because live ingestion may have refreshed the graph.

### Code MCP Tools

- `server_status`: graph inventory, memory counts, vector indexes.
- `code_quality_stats`: compact graph-wide quality and quantity metrics; defaults to non-test code and a 5-row first page.
- `code_hot_paths`: compact hot-path candidates by type size, method size, fan-in, and fan-out; defaults to `limit=5`, `include_evidence=false`, and sections `["fanIn","longestMethods"]` — pass `include_evidence=true` when source location (`path`/`startLine`/`endLine`) is needed.
- `code_orientation`: compact language/package/type/call overview; pass `sections` and small `limit` values.
- `code_search`: CodeChunk RAG discovery; defaults to 5 deduped range-first hits (`kind`, `owner`, `name`, `path`, `startLine`, `endLine`, `score`). Text is omitted unless `include_text=true`.
- `code_lookup_type`: class/interface/annotation details; defaults to `limit=10`, `compact=true`, and `member_summary=false`; members are omitted unless `include_members=true`.
- `code_lookup_methods`: exact method records and source ranges; defaults to `limit=10`, `compact=true` (`owner`, `name`, `path`, `startLine`, `endLine`). Pass `compact=false` only when full signatures/modifiers are needed.
- `code_callers` / `code_callees`: compact, paginated call graph lookup by default; first page defaults to 10 rows. Compact rows are range-first (`owner`/`name` plus `path`, `startLine`, `endLine`, and the opposite side's owner/name) — no follow-up `code_lookup_methods` needed for source ranges. Pass `compact=false` only when full caller/callee signatures are needed.
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
