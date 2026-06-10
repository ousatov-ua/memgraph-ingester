## Knowledge Graph

Repo is indexed in Memgraph as **`{{PROJECT_NAME}}`**. No MCP server is available in this
environment — use the **`mgtools`** CLI from the [`memgraph-ingester-tool`](https://pypi.org/project/memgraph-ingester-tool/)
PyPI package for all graph work. It exposes the same endpoints as `memgraph-ingester-mcp`,
one subcommand per tool. Pass `--project "{{PROJECT_NAME}}"` on every call unless
`MEMGRAPH_TOOLS_PROJECT` is exported.

### Setup

Install once into a local Python virtual environment (Python >= 3.11):

```bash
python3 -m venv .venv-mgtools
.venv-mgtools/bin/pip install --quiet --upgrade memgraph-ingester-tool
.venv-mgtools/bin/mgtools server_status --project "{{PROJECT_NAME}}"   # verify connectivity
```

Always call the venv binary by path (`.venv-mgtools/bin/mgtools …`); do not rely on `PATH`
or shell state persisting between tool invocations. Connection settings come from environment
variables and can be prefixed inline per call:

| Variable | Default |
| --- | --- |
| `MEMGRAPH_TOOLS_BOLT_URI` | `bolt://127.0.0.1:7687` |
| `MEMGRAPH_TOOLS_USERNAME` / `MEMGRAPH_TOOLS_PASSWORD` / `MEMGRAPH_TOOLS_DATABASE` | _none_ |
| `MEMGRAPH_TOOLS_PROJECT` | _none_ (else pass `--project`) |
| `MEMGRAPH_TOOLS_READ_ONLY` | `false` (set `true` to reject writes) |

```bash
MEMGRAPH_TOOLS_BOLT_URI="bolt://127.0.0.1:7687" .venv-mgtools/bin/mgtools \
  code_search "authentication filter" --project "{{PROJECT_NAME}}" --limit 5
```

If `mgtools` cannot be installed or Memgraph is unreachable, switch to regular tools
(grep/glob/source reads) and **mention it**.

### Lookup Order

1. **`mgtools` graph commands** for structure, relationships, symbols, code metadata, RAG discovery.
2. **Source files** only after graph lookup, for line-level detail.
3. **grep/glob** for strings, templates, comments, files absent from the graph.
4. **Other tools** only as a last resort.

The task-fit budgets below govern this order — it is not absolute: graph-first means one small bounded graph pass to identify targets, then source. Task already narrowed to known files/ranges (patch work, edit–compile–test loops, post-review fixes) → straight to source/Git is correct, not a violation. This waives only *discovery* (RAG probes, orientation, flow/context lookups), never the mandatory triggers: `code_test_context` for CI/test triage, `code_impact` for signature changes, `code_hierarchy` for declaration changes, `server_status` for status/pending-work. Do not use the graph CLI as a ritual prefix to work it cannot speed up.

If the graph returns no relevant rows, fall back to text search and say why.

### Code Triggers

- **NO DELEGATION:** never delegate architecture analysis, codebase investigations, member/caller lookups, or graph queries to subagents — run `mgtools` yourself.
- **Status/pending-work:** `server_status` or the focused code/memory command first, then Git when local changes matter; Git alone only when explicitly asked for Git-only status.
- **No ritual analysis:** `code_orientation` only when broad structure is needed; prefer focused commands.
- **No double discovery:** graph results and source reads must divide the work, not repeat it. Graph returned the needed members/ranges/call edges → read at most those ranges; do not re-read whole files to re-confirm. Full file source needed anyway → read it directly, not via `code_lookup_type --include-members` or RAG probes; paying for both on the same facts is the most expensive anti-pattern.
- **Diminishing-returns stop:** two consecutive `code_search`/`code_text_search` probes overlapping or empty → stop probing, switch to exact lookups or source; do not reformulate the same concept a third time.
- **Task-fit budgets:** take precedence over the Lookup Order — once spent, continue in source even if graph questions remain. Small well-named feature/onboarding work: at most 1 `code_flow_context` or 1 `code_search` plus 1 exact lookup before source; files identified → stop querying the graph. Known-path work: 1 `code_file_context` before source. Patch/CI/test repair: at most 1 focused graph lookup before Git/source; worktree diffs may be the highest-signal evidence. Implementation fixes with concrete terms: at most 1 `code_text_search` plus 1 exact lookup before source; do not start with `code_flow_context` unless concept-first without concrete identifiers. Concept-first implementation fix (symptom-only, no class names): `code_flow_context` (1 call) + `code_test_context` if a test is mentioned (1 call) + up to 1 exact lookup on the most likely target — hard cap 4 graph calls total; after that, read all needed files in ONE batched step, then edit. Hot-path/performance: at most 1 `code_hot_paths` plus 1 `code_operation_hot_paths`; add 1 `code_resource_risk_scan` when query/resource complexity is in scope; inspect only the top 2-3 targets. Refactor blast-radius: `code_impact` first with `--view files` (see Refactor impact). Concept-first workflow work: at most 1 `code_flow_context` before exact lookups or source. Exceed a budget only when a result is missing, ambiguous, or paginated and the extra rows are needed.
- **Edit-loop discipline:** once an edit–compile–test loop starts, stop graph discovery; the only mid-loop triggers are `code_impact` (signature change), `code_test_context` (new test failure), `code_hierarchy` (declaration change). Turn count, not per-call payload, dominates session cost: each new turn re-reads the full accumulated context. Batch independent lookups into a single shell invocation (`mgtools cmd1 …; mgtools cmd2 …`) and independent file reads/greps into a single step; after graph discovery, plan all files needed and read them in ONE batched step — do not trickle file reads across turns. Read a file that will be edited or consulted repeatedly fully once instead of accumulating small ranged reads — a third ranged read of the same file means it should have been read whole.
- **Audits/hot paths:** hot-path/performance questions → start with `code_hot_paths` plus `code_operation_hot_paths` (compact, first-page defaults). Source location needed → `code_hot_paths --include-evidence` returns `path/startLine/endLine` directly; no follow-up `code_lookup_methods`. `code_operation_hot_paths` catches sink-heavy methods calling operation APIs even with low fan-in; subsystem named (writer, storage, parser, adapter, controller) → pass `--owner-fragment`/`--path-contains`. `code_resource_risk_scan` for Cypher/SQL/config/resource complexity leads; source-verify its heuristic rows. `code_quality_stats` only for graph-wide baselines. After those three: source-verify at most 2-3 top-priority suspects and stop — do not call `code_method_context` per suspect in a loop; the pre-ranked results are the ranking.
- **Reuse:** reuse session-scoped graph results unless source files changed, the user asks for refresh, memory changed, or scope changed.
- **Broad/unfamiliar code:** workflow/feature-path/cross-file question → 1 concise `code_flow_context` first; one or two semantic anchors enough → `code_discovery_context`; raw RAG anchors enough → `code_search` only; concrete words likely in code/comments/templates → `code_text_search`. Defaults compact (`--limit 3` to `--limit 5`, no `--include-text`); lexical rows ranked by `termMatches`; RAG/text discovery prefers primary/file chunks. `--include-secondary` or explicit `--rag-roles` only for constants, config/member fields, record components, accessors, synthetic/module chunks. RAG/text hits are discovery only — verify with exact lookup or source before claims.
- **Known targets:** skip RAG; exact commands with precise fragments and compact defaults: `code_lookup_type`, `code_lookup_methods`, `code_lookup_field`, `code_lookup_file`, `code_file_context`, `code_callers`, `code_callees`, `code_impact`, `code_test_context`, `code_hierarchy`.
- **Implementation fixes:** concrete properties/methods/files/terms named or implied → prefer `code_text_search`/`code_lookup_*` over `code_flow_context`; likely edit files identified → stop graph expansion, read only the needed ranges. `code_flow_context` is for symptom-only flow discovery, not already-narrowed patch work.
- **Call graph tracing:** prefer `code_method_context` over separate `code_lookup_methods` + `code_callers` + `code_callees` for one method target. Do not loop it over every method in a flow — after 2-3 method contexts identify the participating files, read their relevant ranges; a per-method loop costs more than the source reads it replaces. The 2-3 stop applies only to flow tracing from method targets; hot-path/performance suspects → the Audits/hot paths rule wins: pre-ranked results are the ranking, zero `code_method_context` calls per suspect.
- **Refactor impact:** `code_impact` for method signature changes before separate caller/callee lookups. Keep `--include-tests true` (the default) for blast radius; check `meta.hasMore`; multiple `targetMethods` = ambiguity, resolve before editing. Risk-ranked file list wanted → `--view files` returns the pre-ranked deduplicated file list (role, depth, testCallerCount, crossPackageCount, risk) directly. All `code_impact` results are authoritative for call-graph edges and cross-boundary flags; do not open source to confirm relationships already in the response.
- **CI/test triage:** `code_test_context` first with the failing test/class fragment. `meta.exactMatches == 0` → rows are fuzzy context only; immediately inspect the test file plus `git status`/`git diff` before expanding graph exploration (test may be unindexed, renamed, deleted, or worktree-only). Exact test missing → inspect nearby test files and local changes before deciding adding a test suffices. A newly passing test alone is not proof of a production fix unless the prompt only asks for coverage.
- **Fields/config/resource files:** `code_lookup_field` for constants, config fields, member variables; `code_lookup_file` for quick indexed source/resource path discovery; `code_file_context` for a file's top definitions/chunks before source (including templates and Cypher files), before falling back to grep/glob.
- **Large results:** keep compact first pages; `meta.hasMore` true → paginate with `--skip <meta.nextSkip>` instead of raising limits preemptively.
- **Tests:** code commands exclude test paths by default (`src/test/`, `test/`, `tests/`, `/test/`, `/tests/` where supported); `--include-tests` only for test coverage, test repair, or a missed expected production symbol.
- **Row-heavy output:** row-heavy commands default to `--format table_json`; read as `cols` plus `rows`. `--format json` only when raw object format is required.
- **Response conventions:** compact JSON on stdout — **absent keys = null or empty**; never treat a missing attribute as an error. `meta.nextSkip` present only when `meta.hasMore` is `true`. `meta.totalCount` omitted by default on lookup/call-graph commands; `--include-count` only when the full count is needed. All-null `table_json` columns omitted entirely. Errors print `{"error": "..."}` to stderr with exit code 1.
- **Type lookup compaction:** `code_lookup_type` defaults `--compact true` without member summary; `--compact false`/`--member-summary` only when visibility, framework, or member counts are needed; add `--include-members` only when member rows are needed.
- **Before source-code changes:** `code_search` first when broad/unfamiliar; smallest exact command set when known. Dirty-worktree bug fixes → check Git after the first focused graph/source lookup so graph context does not obscure an obvious local regression.
- **Schema/backfill changes:** adding a persisted field, graph property, serialized field, or resource column → explicitly handle existing records lacking the new value. Missing newly-added state should usually force recomputation/backfill or a conservative safe path; do not preserve a known-buggy fast path merely for backward compatibility.
- **Class/interface declaration changes:** call `code_hierarchy` before changing inheritance, implemented interfaces, constructors, or overridden APIs.
- **Method bodies:** `code_lookup_methods` for `startLine`/`endLine`, then read only that range — for one-shot inspection; a file that will be edited or revisited → read it fully once instead (see Edit-loop discipline).
- **After edits:** source changed and relationships needed again → re-query the graph; live ingestion may have refreshed it.

### Code Commands

All commands accept `--project NAME` and `--format json|table_json`. Boolean filters that are
off by default are plain switches (`--include-tests`, `--include-text`, `--include-secondary`,
`--include-count`, `--include-evidence`, `--include-members`, `--member-summary`); list filters
take comma-separated values (`--kinds`, `--sections`, `--path-prefixes`, `--rag-roles`,
`--all-terms`, `--any-terms`, `--extensions`, `--sink-fragments`). `--compact` and
`code_impact --include-tests` take explicit `true|false` values.

- `mgtools server_status`: graph inventory, memory counts, vector indexes.
- `mgtools code_quality_stats`: graph-wide quality/quantity metrics; non-test, 5-row first page.
- `mgtools code_hot_paths [--sections fanIn,longestMethods,fanOut] [--include-evidence]`: candidates by type size, method size, fan-in, fan-out; default `--limit 5`.
- `mgtools code_orientation [--sections languages,packages,largestTypes,crossOwnerCalls] [--limit N]`: language/package/type/call overview; pass sections and small limits.
- `mgtools code_search "QUERY" [--limit 5] [--kinds …] [--path-prefixes …] [--path-contains …] [--owner-fragment …] [--min-score F]`: CodeChunk RAG discovery; 5 non-test deduped primary/file-role range-first hits (`kind`, `owner`, `name`, `path`, `startLine`, `endLine`, `score`); text/source keys only via `--include-text`.
- `mgtools code_text_search [--query "…"] [--all-terms a,b] [--any-terms a,b] [--path-contains …]`: ranked lexical search over indexed chunk text/path/source ids — use where grep would otherwise be used; compact rows with `termMatches`, text omitted.
- `mgtools code_discovery_context "QUERY" [--limit 3] [--neighbor-limit 3]`: top semantic anchors plus bounded exact/caller/callee/file context; prefer over multiple separate RAG + lookup calls.
- `mgtools code_flow_context "QUERY" [--limit-files 3] [--detail compact|full]`: workflow-first discovery (semantic + lexical anchors, compact file outlines, call edges touching selected files); identifies likely hot files in one response before source reads; avoid for concrete implementation fixes where lexical/exact lookup is cheaper.
- `mgtools code_lookup_type (--type-name NAME | --fqn FQN) [--include-members] [--member-summary] [--compact true|false]`: class/interface/annotation details; default `--limit 10`, compact, non-test.
- `mgtools code_lookup_methods "FRAGMENT" [--skip N] [--limit 10] [--compact true|false]`: exact method records and source ranges (`owner`, `name`, `path`, `startLine`, `endLine`); rows whose owner exactly matches a fragment term rank first; `--compact false` only for full signatures/modifiers. To enumerate one class's methods use `code_lookup_type --include-members` or `code_file_context`, not pagination here.
- `mgtools code_lookup_field "FRAGMENT"`: fields/constants by FQN or name fragment; compact non-test paginated rows (owner, name, FQN, path, source range when available).
- `mgtools code_lookup_file "PATH_FRAGMENT"`: indexed files/resources by path fragment; compact non-test paginated rows, language plus definition/chunk counts.
- `mgtools code_file_context FRAGMENT [FRAGMENT…] [--limit-files 5] [--symbol-limit 8]`: deterministic file outline by path fragments (language, definition/chunk counts, RAG role counts, bounded top types/methods/fields); use before opening known files when line-level source not yet needed.
- `mgtools code_callers "CALLEE_FRAGMENT"` / `mgtools code_callees "CALLER_FRAGMENT"`: compact paginated call graph; first page 10 non-test rows, range-first — no follow-up `code_lookup_methods` needed; `--compact false` only for full signatures.
- `mgtools code_method_context "FRAGMENT" [--method-limit 5] [--neighbor-limit 5]`: bundled exact method lookup plus compact callers/callees.
- `mgtools code_impact "FRAGMENT" [--depth 2] [--view callers|files] [--include-tests true|false]`: blast-radius for matching method signatures — target methods plus depth-1/depth-2 callers, test flags, file/package boundary flags; `--view files` returns the pre-ranked file list instead of caller rows.
- `mgtools code_operation_hot_paths [--sink-fragments run,query,write] [--owner-fragment …] [--path-contains …]`: performance suspects by calls into operation-like sinks; pair with `code_hot_paths` for performance/write-path audits.
- `mgtools code_resource_risk_scan [--path-contains …] [--extensions cypher,sql]`: heuristic resource/query scan (unbounded graph traversals, repeated subquery blocks, per-row optional matches, broad writes, write-in-loop); rows are leads — source-verify before claims.
- `mgtools code_test_context "TEST_FRAGMENT" [--production-limit 5]`: matching tests, nearby test files, production callees; use before adding or changing tests from a failing test name.
- `mgtools code_hierarchy FQN`: parents, implemented interfaces, children, ancestors, interface implementors.
- `mgtools raw_read_cypher "QUERY" [--parameters '{"k":"v"}'] [--limit 200]`: read-only, project-scoped Cypher for rare gaps only.

Examples:

```bash
M=.venv-mgtools/bin/mgtools
"$M" code_lookup_methods "GraphWriter.upsertFile" --project "{{PROJECT_NAME}}"
"$M" code_impact "upsertFile" --view files --project "{{PROJECT_NAME}}"
"$M" code_text_search --all-terms "embedding,chunk" --project "{{PROJECT_NAME}}"
"$M" raw_read_cypher "MATCH (f:File {project: \$project}) RETURN f.path LIMIT 10" --project "{{PROJECT_NAME}}"
```

### Tagged Files

`@` paths hint at scope only. For code work, query the graph when structure or relationships are relevant, then open files for exact line-level detail.

### Query Caveats

- Memgraph reverse() is string-only; don't use it for lists.
- Missing `CALLS` edges do not prove no relationship; dynamic or unresolved calls may be absent.
- Java constructors use `name = '<init>'`; implicit default constructors and record accessors can be synthetic.
- Method signature fragments can match overloads. Resolve ambiguity with `code_lookup_methods --compact false` or `code_impact` `targetMethods` before changing code.
- JS/TS modules can be synthetic `:Class` owners; filter `kind = 'class'` for real classes when needed.
- Python and ctags fallback data may be inventory-oriented and may not include calls, imports, inheritance, or decorators.
- Prefer `ownerFqn` and `ownerDisplayName` from command results instead of parsing signatures.
