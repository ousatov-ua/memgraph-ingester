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
- **Task-fit budgets:** for small, well-named feature/onboarding work, use at most 1 `code_flow_context` or 1 `code_search` plus 1 exact MCP lookup before reading source; if those calls identify the files, stop querying the graph. For known-path work, use 1 `code_file_context` before source. For hot-path/performance work, use at most 1 `code_hot_paths` call plus 1 `code_operation_hot_paths` call; add 1 `code_resource_risk_scan` call when query/resource complexity is in scope, then inspect only the top 2-3 targets. For refactor blast-radius work, use `code_impact` first. For concept-first workflow work, use at most 1 `code_flow_context` before switching to exact lookups or source. Exceed these budgets only when a result is missing, ambiguous, or paginated and the extra rows are needed for the answer.
- **Audits/hot paths:** for hot-path/performance questions, start with `code_hot_paths` plus `code_operation_hot_paths`; defaults are compact and first-page only. Use `code_hot_paths(include_evidence=true)` when source location is needed — it returns `path/startLine/endLine` directly, so a follow-up `code_lookup_methods` is not needed. Use `code_operation_hot_paths` to catch sink-heavy methods that call operation APIs such as run/query/write/read/save/delete even when their fan-in is not high; pass `owner_fragment` or `path_contains` when the question names a subsystem such as writer, storage, parser, adapter, or controller. Use `code_resource_risk_scan` for Cypher/SQL/config/resource complexity leads and source-verify its heuristic rows. Add `code_quality_stats` only when graph-wide baselines are needed.
- **Reuse:** reuse session-scoped graph results unless source files changed, the user asks for refresh, memory changed, or scope changed.
- **Broad/unfamiliar code:** use 1 concise `code_flow_context` query first when the task asks about a workflow, feature path, or cross-file behavior; use `code_discovery_context` when one or two semantic anchors are enough; use `code_search` only when raw RAG anchors are enough, and use `code_text_search` when the concept includes concrete words likely present in code/comments/templates. Defaults are compact (`limit=3-5`, `include_text=false`), lexical rows are ranked by `termMatches`, and RAG/text discovery prefers primary/file chunks. Pass `include_secondary=true` or explicit `rag_roles` only when searching constants, record components, accessors, or synthetic/module chunks. Treat RAG/text hits as discovery only; verify with exact MCP lookup or source before making claims.
- **Known targets:** skip RAG and use exact tools with precise fragments and compact defaults: `code_lookup_type`, `code_lookup_methods`, `code_lookup_field`, `code_lookup_file`, `code_file_context`, `code_callers`, `code_callees`, `code_impact`, `code_test_context`, and `code_hierarchy`.
- **Call graph tracing:** prefer `code_method_context` over separate `code_lookup_methods` + `code_callers` + `code_callees` calls for one method target.
- **Refactor impact:** use `code_impact` for method signature changes before separate caller/callee lookups. Keep `include_tests=true` for blast-radius analysis, check `meta.hasMore`, and treat multiple `targetMethods` as ambiguity to resolve before editing.
- **CI/test triage:** use `code_test_context` first with the failing test/class fragment. If the exact test is missing, inspect nearby test files and production callees before deciding that adding a test is sufficient. A newly passing test alone is not proof of a production fix unless the prompt only asks for coverage.
- **Fields/config/resource files:** use `code_lookup_field` for constants, config fields, and member variables; use `code_lookup_file` for quick indexed source/resource path discovery and `code_file_context` when you need the file's top definitions/chunks before source, including templates and Cypher files, before falling back to grep/glob.
- **Large results:** keep compact first pages; when `meta.hasMore` is true, paginate with `meta.nextSkip` instead of raising limits preemptively.
- **Tests:** code MCP tools exclude `src/test/` by default; pass `include_tests=true` only for test coverage, test repair, or when production-code lookup misses an expected symbol.
- **Row-heavy output:** all row-heavy tools (`code_search`, `code_text_search`, `code_discovery_context`, `code_flow_context`, `code_file_context`, `code_lookup_type`, `code_lookup_methods`, `code_lookup_field`, `code_lookup_file`, `code_callers`, `code_callees`, `code_impact`, `code_hot_paths`, `code_operation_hot_paths`, `code_resource_risk_scan`, `code_quality_stats`, `code_test_context`, `raw_read_cypher`) default to `format="table_json"`; read results as `cols` plus `rows`. Pass `format="json"` only when raw object format is required.
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
- `code_search`: CodeChunk RAG discovery; defaults to 5 non-test, deduped, primary/file-role, range-first hits (`kind`, `owner`, `name`, `path`, `startLine`, `endLine`, `score`). Text and source keys are omitted unless `include_text=true` or `include_keys=true`. Supports compact filters such as `kinds`, `path_prefixes`, `path_contains`, `owner_fragment`, `min_score`, `include_secondary`, and `rag_roles`.
- `code_text_search`: ranked lexical search over indexed chunk text/path/source ids; use when concrete terms are likely present and grep would otherwise be used. Defaults to compact primary/file-role rows with `termMatches` and omitted text; opt into secondary roles for constants/config/member fields.
- `code_discovery_context`: concept-first discovery that returns top semantic anchors plus bounded exact/caller/callee/file context. Prefer it over multiple separate RAG + lookup calls.
- `code_flow_context`: workflow-first discovery that combines semantic anchors, lexical anchors, compact file outlines, and call edges touching selected files. Prefer it for unfamiliar features or cross-file behavior when one response should identify the likely hot files before source reads.
- `code_lookup_type`: class/interface/annotation details; defaults to `limit=10`, `compact=true`, `include_tests=false`, and `member_summary=false`; members are omitted unless `include_members=true`.
- `code_lookup_methods`: exact method records and source ranges; defaults to `limit=10`, `include_tests=false`, `compact=true` (`owner`, `name`, `path`, `startLine`, `endLine`). Pass `compact=false` only when full signatures/modifiers are needed.
- `code_lookup_field`: exact field/constant lookup by FQN or name fragment; defaults to compact, non-test, paginated rows with owner, name, FQN, file path, and source range when available.
- `code_lookup_file`: indexed file/resource lookup by path fragment; defaults to compact, non-test, paginated rows with language plus definition/chunk counts.
- `code_file_context`: deterministic file outline by one or more path fragments; returns language, definition/chunk counts, RAG role counts, and bounded top types/methods/fields. Use it before opening known files when line-level source is not yet needed.
- `code_callers` / `code_callees`: compact, paginated call graph lookup by default; first page defaults to 10 non-test rows. Compact rows are range-first (`owner`/`name` plus `path`, `startLine`, `endLine`, and the opposite side's owner/name) — no follow-up `code_lookup_methods` needed for source ranges. Pass `compact=false` only when full caller/callee signatures are needed.
- `code_method_context`: bundled exact method lookup plus compact callers and callees; defaults to 5 non-test methods and 5 non-test callers/callees. Use it instead of separate lookup/caller/callee calls while tracing one method.
- `code_impact`: refactor blast-radius lookup for matching method signatures; returns target methods plus depth-1/depth-2 callers, test flags, and file/package boundary flags. Defaults to `include_tests=true` and `depth=2`.
- `code_operation_hot_paths`: compact performance suspects by calls into operation-like sinks (`run`, `query`, `write`, `read`, `save`, `delete`, etc.); use with `code_hot_paths` for performance/write-path audits, and narrow with `owner_fragment` or `path_contains` when the task gives a subsystem hint.
- `code_resource_risk_scan`: compact heuristic resource/query scan for patterns such as unbounded graph traversals, repeated subquery blocks, per-row optional matches, broad writes, and write-in-loop constructs. Treat rows as leads; source-verify before making claims.
- `code_test_context`: test/CI triage lookup for matching tests, nearby test files, and production callees. Use before adding or changing tests from a failing test name.
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
- Method signature fragments can match overloads. Resolve ambiguity with `code_lookup_methods(compact=false)` or `code_impact` `targetMethods` before changing code.
- JS/TS modules can be synthetic `:Class` owners; filter `kind = 'class'` for real classes when needed.
- Python and ctags fallback data may be inventory-oriented and may not include calls, imports, inheritance, or decorators.
- Prefer `ownerFqn` and `ownerDisplayName` from MCP results instead of parsing signatures.
