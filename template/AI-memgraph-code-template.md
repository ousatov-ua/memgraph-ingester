## Knowledge Graph

Repo is indexed in Memgraph as **`{{PROJECT_NAME}}`**. Use the `memgraph-ingester-mcp`
server for graph work whenever available; pass `project: "{{PROJECT_NAME}}"` unless the
client is configured with that default.

If the MCP server is unavailable, switch to regular tools and **mention it**.

1. **Memgraph MCP tools** for structure, relationships, symbols, metadata, RAG.
2. **Source files** for lines after graph lookup.
3. **grep/glob** for strings, templates, comments, graph-missing files.
4. **Other tools** last.

Task-fit budgets override this order: MCP-first means one bounded discovery pass, then
source. Known files/ranges and edit-test loops may go straight to source/Git, except
mandatory triggers: `server_status`, `code_test_context`, `code_impact`, `code_hierarchy`.
Do not use graph calls as ritual prefixes.

If the graph returns no relevant rows, fall back to text search and say why.

### Code Triggers

- **Use graph selectively:** start with `server_status` for pending-work/status, `code_orientation`
  only for broad orientation, and exact tools for known targets.
- **Stop waste:** divide work between graph results and source reads. Do not re-read source to
  confirm returned call edges/ranges; after two overlapping/empty `code_search`/`code_text_search`
  probes, switch to exact lookup or source.
- **Stale graph guard:** if graph output contradicts the current worktree (reported method absent,
  stale overload, impossible line range, renamed test), treat the graph row as stale, switch to
  source/Git for that fact, and mention the mismatch. Do not keep expanding graph calls to explain
  stale metadata.
- **Task-fit budgets:** onboarding/workflow: 1 `code_flow_context`/`code_search` + 1 exact lookup;
  known file: 1 `code_file_context`; patch/CI/test repair: 1 focused graph lookup before Git/source;
  concrete fix: 1 `code_text_search` + 1 exact lookup; symptom-only fix: `code_flow_context` +
  mentioned `code_test_context` + up to 1 exact lookup, hard cap 4 graph calls; hot-path/perf:
  `code_hot_paths` + `code_operation_hot_paths`, add `code_resource_risk_scan`, source-verify top
  2-3; signature refactor: `code_impact` with `view="files"` first. Exceed only for ambiguity,
  pagination, or missing data.
- **Edit-loop discipline:** after editing/testing starts, stop graph discovery except `code_impact`
  for signature changes, `code_test_context` for new failures, and `code_hierarchy` for
  declaration/inheritance changes. Batch lookups/reads; read reused files fully once.
- **Onboarding/new language:** identify the existing implementation archetype first (for example
  Java in-process parser, JS/Python module analyzer, ctags fallback). Prefer reusing generic
  module/file/chunk resources and shared writers before proposing language-specific
  Cypher/resources; add language-specific resources only when a real semantic or stale-cleanup gap
  requires them.
- **Hot-path/perf ranking:** prioritize per-row graph traversals, repeated stale-cleanup queries,
  and write paths reached by every file/node before generic repeated `MERGE`s. For resource-heavy
  suspicions, inspect the relevant Cypher/resource source even when `code_resource_risk_scan` is
  empty or sparse; resource indexing can lag or omit text.
- **Discovery rules:** broad concept/workflow → `code_flow_context`; semantic anchors →
  `code_discovery_context` or `code_search`; concrete terms → `code_text_search`. RAG/text hits are
  candidates only; verify exactly or in source. Keep compact defaults, prefer primary/file chunks,
  and use secondary roles only for constants/config/accessors/synthetic chunks.
- **Exact lookup rules:** known targets use `code_lookup_type`, `code_lookup_methods`,
  `code_lookup_field`, `code_lookup_file`, `code_file_context`, `code_callers`, `code_callees`,
  `code_method_context`, `code_impact`, `code_test_context`, or `code_hierarchy`. Prefer
  `code_method_context` for one method's edges; do not loop it over flows. For method source use
  `code_lookup_methods` ranges; enumerate classes with `code_lookup_type(include_members=true)` or
  `code_file_context`.
- **Refactor impact:** `code_impact` is authoritative for direct/depth callers, tests, and
  cross-boundary flags. Keep `include_tests=true`, check `meta.hasMore`, resolve overloads before
  editing, and do not reconfirm call edges in source.
- **CI/test triage:** `code_test_context` first. If `meta.exactMatches == 0`, inspect the test file
  plus `git status`/`git diff` before more graph work. If the named failing test passes locally but
  the task asks for a patch, continue with static regression-surface analysis and add a focused
  regression test for the most likely unpinned behavior; do not stop at no-repro unless no plausible
  source-backed cause remains.
- **Incremental/cache freshness:** for stale-result/change-handling bugs, trace skip predicate →
  stored file state → stale cleanup → chunk replacement → dirty/stale embedding marking →
  vector/index visibility. Check mtime-only or mtime+size shortcuts, content/text hashes,
  analysis/config fingerprints, failed-run cleanup short-circuits, and
  model/dimension/input-metadata changes.
- **Implementation fixes:** choose the narrowest source-backed cause from the investigation, not a
  nearby speculative risk. For stale embeddings, prefer fixes that persist and compare the metadata
  that actually represents the embedded input (for example text hash/config), and make missing new
  properties force one conservative refresh/backfill.
- **Resource/schema/backfill:** use `code_lookup_field`, `code_lookup_file`, and `code_file_context`
  for constants/config/resources. `code_resource_risk_scan` gives leads; source-verify. Persisted
  field/property/column changes must handle existing records lacking the value.
- **Output conventions:** row-heavy tools default to `table_json` (`cols` + `rows`); use `json` only
  when needed. Missing keys mean null/empty. Paginate only when `meta.hasMore`; request counts only
  when needed. Tests are excluded by default; add `include_tests=true` only when relevant. Re-query
  after source changes if relationships matter.

### Code MCP Tools

- `server_status`: graph inventory, memory counts, vector indexes.
- `code_quality_stats`: graph-wide quality/quantity metrics; non-test, 5-row first page.
- `code_hot_paths`: candidates by type size, method size, fan-in, fan-out; defaults `limit=5`,
  `include_evidence=false`, sections `["fanIn","longestMethods","fanOut"]`.
- `code_orientation`: language/package/type/call overview; pass `sections` and small `limit` values.
- `code_search`: CodeChunk RAG discovery; defaults 5 non-test deduped primary/file-role range-first
  hits (`kind`, `owner`, `name`, `path`, `startLine`, `endLine`, `score`); text/source keys only via
  `include_text=true`/`include_keys=true`; filters: `kinds`, `path_prefixes`, `path_contains`,
  `owner_fragment`, `min_score`, `include_secondary`, `rag_roles`.
- `code_text_search`: ranked lexical search over indexed chunk text/path/source ids — use where grep
  would otherwise be used; compact primary/file-role rows with `termMatches`, text omitted.
- `code_discovery_context`: top semantic anchors plus bounded exact/caller/callee/file context;
  prefer over multiple separate RAG + lookup calls.
- `code_flow_context`: workflow-first discovery (semantic + lexical anchors, compact file outlines,
  call edges touching selected files); identifies likely hot files in one response before source
  reads; avoid for concrete implementation fixes where lexical/exact lookup is cheaper.
- `code_lookup_type`: class/interface/annotation details; defaults `limit=10`, `compact=true`,
  `include_tests=false`, `member_summary=false`, `include_count=false`; members only with
  `include_members=true`.
- `code_lookup_methods`: exact method records and source ranges; defaults `limit=10`,
  `include_tests=false`, `compact=true`, `include_count=false` (`owner`, `name`, `path`,
  `startLine`, `endLine`); rows whose owner exactly matches a fragment term rank first;
  `compact=false` only for full signatures/modifiers. To enumerate one class's methods use
  `code_lookup_type(include_members=true)` or `code_file_context`, not pagination here.
- `code_lookup_field`: fields/constants by FQN or name fragment; compact non-test paginated rows (
  owner, name, FQN, path, source range when available), `include_count=false`.
- `code_lookup_file`: indexed files/resources by path fragment; compact non-test paginated rows,
  language plus definition/chunk counts, `include_count=false`.
- `code_file_context`: deterministic file outline by path fragments (language, definition/chunk
  counts, RAG role counts, bounded top types/methods/fields); use before opening known files when
  line-level source not yet needed.
- `code_callers` / `code_callees`: compact paginated call graph; first page 10 non-test rows,
  `include_count=false`; rows range-first (`owner`/`name`, `path`, `startLine`, `endLine`, opposite
  side's owner/name) — no follow-up `code_lookup_methods` needed; `compact=false` only for full
  signatures.
- `code_method_context`: bundled exact method lookup plus compact callers/callees; defaults 5
  non-test methods, 5 non-test callers/callees.
- `code_impact`: blast-radius for matching method signatures — target methods plus depth-1/depth-2
  callers, test flags, file/package boundary flags; defaults `include_tests=true`, `depth=2`;
  `view="files"` returns the pre-ranked file list instead of caller rows.
- `code_operation_hot_paths`: performance suspects by calls into operation-like sinks (`run`,
  `query`, `write`, `read`, `save`, `delete`, etc.); pair with `code_hot_paths` for
  performance/write-path audits.
- `code_resource_risk_scan`: heuristic resource/query scan (unbounded graph traversals, repeated
  subquery blocks, per-row optional matches, broad writes, write-in-loop); rows are leads —
  source-verify before claims.
- `code_test_context`: matching tests, nearby test files, production callees; use before adding or
  changing tests from a failing test name.
- `code_hierarchy`: parents, implemented interfaces, children, ancestors, interface implementors.
- `raw_read_cypher`: read-only, project-scoped Cypher for rare gaps only.

- Inventory/status: `server_status`, `code_quality_stats`, `code_orientation`.
- Discovery candidates: `code_flow_context`, `code_discovery_context`, `code_search`,
  `code_text_search`.
- Exact lookup: `code_lookup_type`, `code_lookup_methods`, `code_lookup_field`, `code_lookup_file`,
  `code_file_context`.
- Edges/impact: `code_callers`, `code_callees`, `code_method_context`, `code_impact`,
  `code_hierarchy`.
- Hot path/resource/test: `code_hot_paths`, `code_operation_hot_paths`, `code_resource_risk_scan`,
  `code_test_context`, `raw_read_cypher`.

Use the MCP tool schema for exact parameters.

### Query Caveats

- Memgraph reverse() is string-only; don't use it for lists.
- Missing `CALLS` edges are not proof; dynamic/unresolved calls may be absent.
- Java constructors are `name = '<init>'`; defaults/accessors can be synthetic.
- Signature fragments can match overloads; resolve with `code_lookup_methods(compact=false)` or
  `code_impact` `targetMethods` before editing.
- JS/TS modules can be synthetic `:Class`; filter real classes with `kind = 'class'`.
- Python/ctags fallback data may be inventory-only. Prefer result `ownerFqn`/`ownerDisplayName` over
  parsed signatures.
