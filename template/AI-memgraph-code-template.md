## Knowledge Graph

Repo is indexed in Memgraph as **`{{PROJECT_NAME}}`**. Use the `memgraph-ingester-mcp` server for graph work whenever available; pass `project: "{{PROJECT_NAME}}"` on every tool call unless the client is configured with that default.

### Lookup Order

1. **Memgraph MCP tools** for structure, relationships, symbols, code metadata, RAG discovery.
2. **Source files** only after graph lookup, for line-level detail.
3. **grep/glob** for strings, templates, comments, files absent from the graph.
4. **Other tools** only as a last resort.

The task-fit budgets below govern this order — it is not absolute: MCP-first means one small bounded graph pass to identify targets, then source. Task already narrowed to known files/ranges (patch work, edit–compile–test loops, post-review fixes) → straight to source/Git is correct, not a violation. This waives only *discovery* (RAG probes, orientation, flow/context lookups), never the mandatory triggers: `code_test_context` for CI/test triage, `code_impact` for signature changes, `code_hierarchy` for declaration changes, `server_status` for status/pending-work. Do not use the MCP as a ritual prefix to work it cannot speed up.

If the MCP returns no relevant rows, fall back to text search and say why.

### Code Triggers

- **NO DELEGATION:** never delegate architecture analysis, codebase investigations, member/caller lookups, or graph queries to subagents — use the MCP yourself.
- **Status/pending-work:** `server_status` or the focused code/memory tool first, then Git when local changes matter; Git alone only when explicitly asked for Git-only status.
- **No ritual analysis:** `code_orientation` only when broad structure is needed; prefer focused tools.
- **No double discovery:** graph results and source reads must divide the work, not repeat it. Graph returned the needed members/ranges/call edges → read at most those ranges; do not re-read whole files to re-confirm. Full file source needed anyway → read it directly, not via `code_lookup_type(include_members=true)` or RAG probes; paying for both on the same facts is the most expensive anti-pattern.
- **Diminishing-returns stop:** two consecutive `code_search`/`code_text_search` probes overlapping or empty → stop probing, switch to exact lookups or source; do not reformulate the same concept a third time.
- **Task-fit budgets:** take precedence over the Lookup Order — once spent, continue in source even if graph questions remain. Small well-named feature/onboarding work: at most 1 `code_flow_context` or 1 `code_search` plus 1 exact lookup before source; files identified → stop querying the graph. Known-path work: 1 `code_file_context` before source. Patch/CI/test repair: at most 1 focused MCP lookup before Git/source; worktree diffs may be the highest-signal evidence. Implementation fixes with concrete terms: at most 1 `code_text_search` plus 1 exact lookup before source; do not start with `code_flow_context` unless concept-first without concrete identifiers. Concept-first implementation fix (symptom-only, no class names): `code_flow_context` (1 call) + `code_test_context` if a test is mentioned (1 call) + up to 1 exact lookup on the most likely target — hard cap 4 MCP calls total; after that, read all needed files in ONE batched message, then edit. Hot-path/performance: at most 1 `code_hot_paths` plus 1 `code_operation_hot_paths`; add 1 `code_resource_risk_scan` when query/resource complexity is in scope; inspect only the top 2-3 targets. Refactor blast-radius: `code_impact` first with `view="files"` (see Refactor impact). Concept-first workflow work: at most 1 `code_flow_context` before exact lookups or source. Exceed a budget only when a result is missing, ambiguous, or paginated and the extra rows are needed.
- **Edit-loop discipline:** once an edit–compile–test loop starts, stop graph discovery; the only mid-loop triggers are `code_impact` (signature change), `code_test_context` (new test failure), `code_hierarchy` (declaration change). Turn count, not per-call payload, dominates session cost: each new turn re-reads the full accumulated cached context, so N turns × K-token context = N×K cache-read tokens billed. Batch independent lookups/reads/greps into a single message; after MCP discovery, plan all files needed and read them in ONE batched message — do not trickle file reads across turns. Read a file that will be edited or consulted repeatedly fully once instead of accumulating small ranged reads — a third ranged read of the same file means it should have been read whole.
- **Audits/hot paths:** hot-path/performance questions → start with `code_hot_paths` plus `code_operation_hot_paths` (compact, first-page defaults). Source location needed → `code_hot_paths(include_evidence=true)` returns `path/startLine/endLine` directly; no follow-up `code_lookup_methods`. `code_operation_hot_paths` catches sink-heavy methods calling operation APIs even with low fan-in; subsystem named (writer, storage, parser, adapter, controller) → pass `owner_fragment`/`path_contains`. `code_resource_risk_scan` for Cypher/SQL/config/resource complexity leads; source-verify its heuristic rows. `code_quality_stats` only for graph-wide baselines. After those three: source-verify at most 2-3 top-priority suspects and stop — do not call `code_method_context` per suspect in a loop; the pre-ranked results are the ranking.
- **Reuse:** reuse session-scoped graph results unless source files changed, the user asks for refresh, memory changed, or scope changed.
- **Broad/unfamiliar code:** workflow/feature-path/cross-file question → 1 concise `code_flow_context` first; one or two semantic anchors enough → `code_discovery_context`; raw RAG anchors enough → `code_search` only; concrete words likely in code/comments/templates → `code_text_search`. Defaults compact (`limit=3-5`, `include_text=false`); lexical rows ranked by `termMatches`; RAG/text discovery prefers primary/file chunks. `include_secondary=true` or explicit `rag_roles` only for constants, config/member fields, record components, accessors, synthetic/module chunks. RAG/text hits are discovery only — verify with exact lookup or source before claims.
- **Known targets:** skip RAG; exact tools with precise fragments and compact defaults: `code_lookup_type`, `code_lookup_methods`, `code_lookup_field`, `code_lookup_file`, `code_file_context`, `code_callers`, `code_callees`, `code_impact`, `code_test_context`, `code_hierarchy`.
- **Implementation fixes:** concrete properties/methods/files/terms named or implied → prefer `code_text_search`/`code_lookup_*` over `code_flow_context`; likely edit files identified → stop graph expansion, read only the needed ranges. `code_flow_context` is for symptom-only flow discovery, not already-narrowed patch work.
- **Call graph tracing:** prefer `code_method_context` over separate `code_lookup_methods` + `code_callers` + `code_callees` for one method target. Do not loop it over every method in a flow — after 2-3 method contexts identify the participating files, read their relevant ranges; a per-method loop costs more than the source reads it replaces. The 2-3 stop applies only to flow tracing from method targets; hot-path/performance suspects → the Audits/hot paths rule wins: pre-ranked results are the ranking, zero `code_method_context` calls per suspect.
- **Refactor impact:** `code_impact` for method signature changes before separate caller/callee lookups. Keep `include_tests=true` for blast radius; check `meta.hasMore`; multiple `targetMethods` = ambiguity, resolve before editing. Risk-ranked file list wanted → `view="files"` returns the pre-ranked deduplicated file list (role, depth, testCallerCount, crossPackageCount, risk) directly. All `code_impact` results are authoritative for call-graph edges and cross-boundary flags; do not open source to confirm relationships already in the response.
- **CI/test triage:** `code_test_context` first with the failing test/class fragment. `meta.exactMatches == 0` → rows are fuzzy context only; immediately inspect the test file plus `git status`/`git diff` before expanding graph exploration (test may be unindexed, renamed, deleted, or worktree-only). Exact test missing → inspect nearby test files and local changes before deciding adding a test suffices. A newly passing test alone is not proof of a production fix unless the prompt only asks for coverage.
- **Fields/config/resource files:** `code_lookup_field` for constants, config fields, member variables; `code_lookup_file` for quick indexed source/resource path discovery; `code_file_context` for a file's top definitions/chunks before source (including templates and Cypher files), before falling back to grep/glob.
- **Large results:** keep compact first pages; `meta.hasMore` true → paginate with `meta.nextSkip` instead of raising limits preemptively.
- **Tests:** code MCP tools exclude test paths by default (`src/test/`, `test/`, `tests/`, `/test/`, `/tests/` where supported); `include_tests=true` only for test coverage, test repair, or a missed expected production symbol.
- **Row-heavy output:** `code_search`, `code_text_search`, `code_discovery_context`, `code_flow_context`, `code_file_context`, `code_lookup_type`, `code_lookup_methods`, `code_lookup_field`, `code_lookup_file`, `code_callers`, `code_callees`, `code_impact`, `code_hot_paths`, `code_operation_hot_paths`, `code_resource_risk_scan`, `code_quality_stats`, `code_hierarchy`, `code_test_context`, `raw_read_cypher` all default to `format="table_json"`; read as `cols` plus `rows`. `format="json"` only when raw object format is required.
- **Response conventions:** compact JSON — **absent keys = null or empty**; never treat a missing attribute as an error. `meta.nextSkip` present only when `meta.hasMore` is `true`. `meta.totalCount` omitted by default on lookup/call-graph tools; `include_count=true` only when the full count is needed. All-null `table_json` columns omitted entirely. Input parameters not echoed back.
- **Type lookup compaction:** `code_lookup_type` defaults `compact=true`, `member_summary=false`; `compact=false`/`member_summary=true` only when visibility, framework, or member counts are needed; keep `include_members=false` unless member rows are needed.
- **Before source-code changes:** `code_search` first when broad/unfamiliar; smallest exact tool set when known. Dirty-worktree bug fixes → check Git after the first focused MCP/source lookup so graph context does not obscure an obvious local regression.
- **Schema/backfill changes:** adding a persisted field, graph property, serialized field, or resource column → explicitly handle existing records lacking the new value. Missing newly-added state should usually force recomputation/backfill or a conservative safe path; do not preserve a known-buggy fast path merely for backward compatibility.
- **Class/interface declaration changes:** call `code_hierarchy` before changing inheritance, implemented interfaces, constructors, or overridden APIs.
- **Method bodies:** `code_lookup_methods` for `startLine`/`endLine`, then read only that range — for one-shot inspection; a file that will be edited or revisited → read it fully once instead (see Edit-loop discipline).
- **After edits:** source changed and relationships needed again → re-query the MCP; live ingestion may have refreshed the graph.

### Code MCP Tools

- `server_status`: graph inventory, memory counts, vector indexes.
- `code_quality_stats`: graph-wide quality/quantity metrics; non-test, 5-row first page.
- `code_hot_paths`: candidates by type size, method size, fan-in, fan-out; defaults `limit=5`, `include_evidence=false`, sections `["fanIn","longestMethods","fanOut"]`.
- `code_orientation`: language/package/type/call overview; pass `sections` and small `limit` values.
- `code_search`: CodeChunk RAG discovery; defaults 5 non-test deduped primary/file-role range-first hits (`kind`, `owner`, `name`, `path`, `startLine`, `endLine`, `score`); text/source keys only via `include_text=true`/`include_keys=true`; filters: `kinds`, `path_prefixes`, `path_contains`, `owner_fragment`, `min_score`, `include_secondary`, `rag_roles`.
- `code_text_search`: ranked lexical search over indexed chunk text/path/source ids — use where grep would otherwise be used; compact primary/file-role rows with `termMatches`, text omitted.
- `code_discovery_context`: top semantic anchors plus bounded exact/caller/callee/file context; prefer over multiple separate RAG + lookup calls.
- `code_flow_context`: workflow-first discovery (semantic + lexical anchors, compact file outlines, call edges touching selected files); identifies likely hot files in one response before source reads; avoid for concrete implementation fixes where lexical/exact lookup is cheaper.
- `code_lookup_type`: class/interface/annotation details; defaults `limit=10`, `compact=true`, `include_tests=false`, `member_summary=false`, `include_count=false`; members only with `include_members=true`.
- `code_lookup_methods`: exact method records and source ranges; defaults `limit=10`, `include_tests=false`, `compact=true`, `include_count=false` (`owner`, `name`, `path`, `startLine`, `endLine`); rows whose owner exactly matches a fragment term rank first; `compact=false` only for full signatures/modifiers. To enumerate one class's methods use `code_lookup_type(include_members=true)` or `code_file_context`, not pagination here.
- `code_lookup_field`: fields/constants by FQN or name fragment; compact non-test paginated rows (owner, name, FQN, path, source range when available), `include_count=false`.
- `code_lookup_file`: indexed files/resources by path fragment; compact non-test paginated rows, language plus definition/chunk counts, `include_count=false`.
- `code_file_context`: deterministic file outline by path fragments (language, definition/chunk counts, RAG role counts, bounded top types/methods/fields); use before opening known files when line-level source not yet needed.
- `code_callers` / `code_callees`: compact paginated call graph; first page 10 non-test rows, `include_count=false`; rows range-first (`owner`/`name`, `path`, `startLine`, `endLine`, opposite side's owner/name) — no follow-up `code_lookup_methods` needed; `compact=false` only for full signatures.
- `code_method_context`: bundled exact method lookup plus compact callers/callees; defaults 5 non-test methods, 5 non-test callers/callees.
- `code_impact`: blast-radius for matching method signatures — target methods plus depth-1/depth-2 callers, test flags, file/package boundary flags; defaults `include_tests=true`, `depth=2`; `view="files"` returns the pre-ranked file list instead of caller rows.
- `code_operation_hot_paths`: performance suspects by calls into operation-like sinks (`run`, `query`, `write`, `read`, `save`, `delete`, etc.); pair with `code_hot_paths` for performance/write-path audits.
- `code_resource_risk_scan`: heuristic resource/query scan (unbounded graph traversals, repeated subquery blocks, per-row optional matches, broad writes, write-in-loop); rows are leads — source-verify before claims.
- `code_test_context`: matching tests, nearby test files, production callees; use before adding or changing tests from a failing test name.
- `code_hierarchy`: parents, implemented interfaces, children, ancestors, interface implementors.
- `raw_read_cypher`: read-only, project-scoped Cypher for rare gaps only.

If `memgraph-ingester-mcp` tool is not available switch to regular tools and **mention it**.

### Tagged Files

`@` paths hint at scope only. For code work, query the MCP when structure or relationships are relevant, then open files for exact line-level detail.

### Query Caveats

- Memgraph reverse() is string-only; don't use it for lists.
- Missing `CALLS` edges do not prove no relationship; dynamic or unresolved calls may be absent.
- Java constructors use `name = '<init>'`; implicit default constructors and record accessors can be synthetic.
- Method signature fragments can match overloads. Resolve ambiguity with `code_lookup_methods(compact=false)` or `code_impact` `targetMethods` before changing code.
- JS/TS modules can be synthetic `:Class` owners; filter `kind = 'class'` for real classes when needed.
- Python and ctags fallback data may be inventory-oriented and may not include calls, imports, inheritance, or decorators.
- Prefer `ownerFqn` and `ownerDisplayName` from MCP results instead of parsing signatures.
