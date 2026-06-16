## Knowledge Graph

Repo is indexed in Memgraph as **`{{PROJECT_NAME}}`**. Without MCP, use the **`mgtools`**
CLI from `memgraph-ingester-tool`; it exposes MCP endpoints as subcommands. Pass
`--project "{{PROJECT_NAME}}"` unless `MEMGRAPH_TOOLS_PROJECT` is exported.

Before running any `mgtools` command in a checkout, ensure the project-local venv exists and
verify connectivity. This setup is required, not optional: if `.venv-mgtools/bin/mgtools`
(`.venv-mgtools\Scripts\mgtools` on Windows) is missing, run the install commands first. If the
venv already exists, run the `pip install --upgrade memgraph-ingester-tool` command before
the first `mgtools` call in the session.

```bash
python3 -m venv .venv-mgtools
.venv-mgtools/bin/pip install --quiet --upgrade memgraph-ingester-tool
.venv-mgtools/bin/mgtools server_status --project "{{PROJECT_NAME}}"
```

Always call the venv binary by path (`.venv-mgtools/bin/mgtools`, or
`.venv-mgtools\Scripts\mgtools` on Windows); do not rely on `PATH`, global `mgtools`,
`uv tool run`, `uvx`, or `pipx` while the venv setup can be created or repaired. Env vars:
`MEMGRAPH_TOOLS_BOLT_URI` (default `bolt://127.0.0.1:7687`), username/password/database/project,
read-only mode.

If the venv install/repair fails, Python >= 3.11 is unavailable, or Memgraph is unavailable,
switch to regular tools and **mention the exact reason and fallback used**.

1. **`mgtools` graph commands** for structure, relationships, symbols, metadata, RAG.
2. **Source files** for lines after graph lookup.
3. **grep/glob** for strings, templates, comments, graph-missing files.
4. **Other tools** last.

Task-fit budgets override this order: graph-first means one bounded discovery pass, then
source. Known files/ranges and edit-test loops may go straight to source/Git, except
mandatory triggers: `server_status`, `code_test_context`, `code_impact`, `code_hierarchy`.
Do not use graph calls as ritual prefixes.

If the graph returns no relevant rows, fall back to text search and say why.

### Code Triggers

- **Use graph selectively:** start with `server_status` for pending-work/status, `code_orientation`
  only for broad orientation, and exact commands for known targets.
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
  2-3; signature refactor: `code_impact --view files` first. Exceed only for ambiguity, pagination,
  or missing data.
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
  `code_lookup_methods` ranges; enumerate classes with `code_lookup_type --include-members` or
  `code_file_context`.
- **Refactor impact:** `code_impact` is authoritative for direct/depth callers, tests, and
  cross-boundary flags. Keep `--include-tests true`, check `meta.hasMore`, resolve overloads before
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
- **Output conventions:** row-heavy commands default to `table_json` (`cols` + `rows`); use `json`
  only when needed. Missing keys mean null/empty. Paginate only when `meta.hasMore`; request counts
  only when needed. Tests are excluded by default; add `--include-tests` only when relevant.
  Re-query after source changes if relationships matter.

### Code Commands

All commands accept `--project NAME` and `--format json|table_json`. Boolean filters that are
off by default are plain switches (`--include-tests`, `--include-text`, `--include-secondary`,
`--include-count`, `--include-evidence`, `--include-members`, `--member-summary`); list filters
take comma-separated values (`--kinds`, `--sections`, `--path-prefixes`, `--rag-roles`,
`--all-terms`, `--any-terms`, `--extensions`, `--sink-fragments`). `--compact` and
`code_impact --include-tests` take explicit `true|false` values.

- `mgtools server_status`: graph inventory, memory counts, vector indexes.
- `mgtools code_quality_stats`: graph-wide quality/quantity metrics; non-test, 5-row first page.
- `mgtools code_hot_paths [--sections fanIn,longestMethods,fanOut] [--include-evidence]`: candidates
  by type size, method size, fan-in, fan-out; default `--limit 5`.
-
`mgtools code_orientation [--sections languages,packages,largestTypes,crossOwnerCalls] [--limit N]`:
language/package/type/call overview; pass sections and small limits.
-
`mgtools code_search "QUERY" [--limit 5] [--kinds …] [--path-prefixes …] [--path-contains …] [--owner-fragment …] [--min-score F]`:
CodeChunk RAG discovery; 5 non-test deduped primary/file-role range-first hits (`kind`, `owner`,
`name`, `path`, `startLine`, `endLine`, `score`); text/source keys only via `--include-text`.
- `mgtools code_text_search [--query "…"] [--all-terms a,b] [--any-terms a,b] [--path-contains …]`:
  ranked lexical search over indexed chunk text/path/source ids — use where grep would otherwise be
  used; compact rows with `termMatches`, text omitted.
- `mgtools code_discovery_context "QUERY" [--limit 3] [--neighbor-limit 3]`: top semantic anchors
  plus bounded exact/caller/callee/file context; prefer over multiple separate RAG + lookup calls.
- `mgtools code_flow_context "QUERY" [--limit-files 3] [--detail compact|full]`: workflow-first
  discovery (semantic + lexical anchors, compact file outlines, call edges touching selected files);
  identifies likely hot files in one response before source reads; avoid for concrete implementation
  fixes where lexical/exact lookup is cheaper.
-
`mgtools code_lookup_type (--type-name NAME | --fqn FQN) [--include-members] [--member-summary] [--compact true|false]`:
class/interface/annotation details; default `--limit 10`, compact, non-test.
- `mgtools code_lookup_methods "FRAGMENT" [--skip N] [--limit 10] [--compact true|false]`: exact
  method records and source ranges (`owner`, `name`, `path`, `startLine`, `endLine`); rows whose
  owner exactly matches a fragment term rank first; `--compact false` only for full
  signatures/modifiers. To enumerate one class's methods use `code_lookup_type --include-members` or
  `code_file_context`, not pagination here.
- `mgtools code_lookup_field "FRAGMENT"`: fields/constants by FQN or name fragment; compact non-test
  paginated rows (owner, name, FQN, path, source range when available).
- `mgtools code_lookup_file "PATH_FRAGMENT"`: indexed files/resources by path fragment; compact
  non-test paginated rows, language plus definition/chunk counts.
- `mgtools code_file_context FRAGMENT [FRAGMENT…] [--limit-files 5] [--symbol-limit 8]`:
  deterministic file outline by path fragments (language, definition/chunk counts, RAG role counts,
  bounded top types/methods/fields); use before opening known files when line-level source not yet
  needed.
- `mgtools code_callers "CALLEE_FRAGMENT"` / `mgtools code_callees "CALLER_FRAGMENT"`: compact
  paginated call graph; first page 10 non-test rows, range-first — no follow-up
  `code_lookup_methods` needed; `--compact false` only for full signatures.
- `mgtools code_method_context "FRAGMENT" [--method-limit 5] [--neighbor-limit 5]`: bundled exact
  method lookup plus compact callers/callees.
- `mgtools code_impact "FRAGMENT" [--depth 2] [--view callers|files] [--include-tests true|false]`:
  blast-radius for matching method signatures — target methods plus depth-1/depth-2 callers, test
  flags, file/package boundary flags; `--view files` returns the pre-ranked file list instead of
  caller rows.
-
`mgtools code_operation_hot_paths [--sink-fragments run,query,write] [--owner-fragment …] [--path-contains …]`:
performance suspects by calls into operation-like sinks; pair with `code_hot_paths` for
performance/write-path audits.
- `mgtools code_resource_risk_scan [--path-contains …] [--extensions cypher,sql]`: heuristic
  resource/query scan (unbounded graph traversals, repeated subquery blocks, per-row optional
  matches, broad writes, write-in-loop); rows are leads — source-verify before claims.
- `mgtools code_test_context "TEST_FRAGMENT" [--production-limit 5]`: matching tests, nearby test
  files, production callees; use before adding or changing tests from a failing test name.
- `mgtools code_hierarchy FQN`: parents, implemented interfaces, children, ancestors, interface
  implementors.
- `mgtools raw_read_cypher "QUERY" [--parameters '{"k":"v"}'] [--limit 200]`: read-only,
  project-scoped Cypher for rare gaps only.

- Inventory/status: `server_status`, `code_quality_stats`, `code_orientation`.
- Discovery candidates: `code_flow_context`, `code_discovery_context`, `code_search`,
  `code_text_search`.
- Exact lookup: `code_lookup_type`, `code_lookup_methods`, `code_lookup_field`, `code_lookup_file`,
  `code_file_context`.
- Edges/impact: `code_callers`, `code_callees`, `code_method_context`, `code_impact`,
  `code_hierarchy`.
- Hot path/resource/test: `code_hot_paths`, `code_operation_hot_paths`, `code_resource_risk_scan`,
  `code_test_context`, `raw_read_cypher`.

### Query Caveats

- Memgraph reverse() is string-only; don't use it for lists.
- Missing `CALLS` edges are not proof; dynamic/unresolved calls may be absent.
- Java constructors are `name = '<init>'`; defaults/accessors can be synthetic.
- Signature fragments can match overloads; resolve with `code_lookup_methods --compact false` or
  `code_impact` `targetMethods` before editing.
- JS/TS modules can be synthetic `:Class`; filter real classes with `kind = 'class'`.
- Python/ctags fallback data may be inventory-only. Prefer result `ownerFqn`/`ownerDisplayName` over
  parsed signatures.
