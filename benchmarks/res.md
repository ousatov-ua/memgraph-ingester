# Memgraph MCP Comparison Experiment — Results

**Workflow:** `wf_0bdb3577-ba6`  
**Session:** `ef5ecbe4-2ae9-4d3a-baa5-d28d39895522`  
**Date:** 2026-06-10  
**Tasks:** A, B, C, D, E, F (6 tasks × 2 runs = 12 agents)

---

## 1. Methodology

### Transcript source and agent selection

Transcript files live in:

```
/Users/Oleksii_Usatov/.claude/projects/-Users-Oleksii-Usatov-my-memgraph-ingester/
  ef5ecbe4-2ae9-4d3a-baa5-d28d39895522/subagents/workflows/wf_0bdb3577-ba6/
```

The directory contained 13 `agent-*.jsonl` files. Each file was inspected for a first
`type:"user"` line whose `message.content` string contained both `taskId="X"` (A–F) and
`runType="with-memgraph"` or `runType="without-memgraph"`. Exactly 12 files carried those
markers and were selected:

| File | Task | Run |
|------|------|-----|
| agent-a0ba040157390542c.jsonl | A | with-memgraph |
| agent-a73268a13ea8583a6.jsonl | A | without-memgraph |
| agent-a37da2983a2c47010.jsonl | B | with-memgraph |
| agent-a022ea222220488d4.jsonl | B | without-memgraph |
| agent-a0f35625f7a5defc7.jsonl | C | with-memgraph |
| agent-a264fd88fb7bcccd9.jsonl | C | without-memgraph |
| agent-a53e5ab58a3f095aa.jsonl | D | with-memgraph |
| agent-a5f62b65d0ce6f287.jsonl | D | without-memgraph |
| agent-a7fadc9589480f625.jsonl | E | with-memgraph |
| agent-af37c18e322fd8dd5.jsonl | E | without-memgraph |
| agent-a2cf67d4b9a446abd.jsonl | F | with-memgraph |
| agent-ad22e97b34a4da85f.jsonl | F | without-memgraph |

**Discarded:** `agent-aed8ddbc01ab79907.jsonl` — no `taskId`/`runType` markers; this is the
stats/orchestrator agent itself.

### Token measurement

All quantitative metrics are derived from the raw JSONL transcripts (ground truth), not
self-reported by any agent.

**Per-message grouping:** all `type:"assistant"` lines are grouped by `message.id`. For each
group the **maximum** value of each counter is taken across all streaming delta lines:
`output_tokens`, `input_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`.
This removes duplicate partial-usage rows emitted during streaming.

**Text-length estimation:** for each message group a `char_count` is computed as the sum of
`len(text blocks)` plus `len(json.dumps(tool_use.input))` for all content blocks. If
`char_count > 200` AND the recorded `max_output_tokens < 0.25 × (char_count / 3.3)`, the
output token count is replaced with `int(char_count / 3.3)` and the message is flagged as
estimated. The factor 3.3 chars/token approximates the Claude tokenizer for mixed
prose/code.

**Definitions:**
- **Output tokens** — primary generation cost; highest-rate token type.
- **Cache-read tokens** — previously cached context re-read at 0.30/M (Sonnet) or 0.50/M
  (Opus); dominates the billed footprint in long sessions.
- **Total billed footprint** — sum of all four token types (output + input + cache_creation
  + cache_read).
- **Messages estimated** — count of messages whose output token count was replaced by the
  char-count heuristic.
- **Estimated tokens** — total output tokens contributed by estimated messages.

**Tool call counting:**
- `tool_calls` — count of distinct `tool_use` block IDs across all content in the
  transcript (both assistant and tool-result messages are scanned).
- `files_opened` — count of distinct `file_path` values in `Read` tool `input` blocks.
- `mcp_calls` — count of `tool_use` blocks whose `name` starts with `mcp__memgraph`.
- **Isolation check:** all without-memgraph runs must show 0 MCP calls. This is confirmed
  for every without-memgraph run in this experiment.

### Pricing

| Model | Input | Cache creation | Cache read | Output |
|-------|-------|----------------|------------|--------|
| Sonnet 4.6 | $3.00/M | $3.75/M | $0.30/M | $15.00/M |
| Opus 4.8 | $5.00/M | $6.25/M | $0.50/M | $25.00/M |

**Cost formula:**

```
cost = (input_tokens × input_rate
      + cache_creation_tokens × write_rate
      + cache_read_tokens × read_rate
      + output_tokens × output_rate) / 1_000_000
```

---

## 2. Per-run output

### Task A — with-memgraph

**Findings summary:**

The with-memgraph agent identified the full six-layer architecture needed for first-class Go
support: `LanguageAdapter<T>` contract, `AbstractModuleLanguageAdapter`, `ModuleAnalysis`
VO, `ModuleGraphWriter`, `ModuleCodeChunkBuilder`, and the managed runtime download
pattern. It determined that Go should follow the module-oriented (Python/JS) archetype, not
the ctags fallback, and produced a precise four-file implementation plan (new files: 14;
modified entry points: `IngesterCli`, `LanguageAdapterFactory`, `SourceLanguage`,
`Const`). It used `code_hot_paths`, `code_flow_context`, and `code_lookup_type` to navigate
the adapter hierarchy before opening source, reducing redundant file reads.

**Metrics:**

| Metric | Value |
|--------|-------|
| Output tokens | 15,159 |
| Cache-read tokens | 810,169 |
| Total billed footprint | 907,963 |
| Tool calls | 55 |
| MCP calls | 28 |
| Files opened | 22 |
| Cost — Sonnet 4.6 | $0.7803 |
| Cost — Opus 4.8 | $1.3005 |
| Messages estimated | 8 (1,432 estimated tokens) |

---

### Task A — without-memgraph

**Findings summary:**

The without-memgraph agent independently reconstructed the same six-layer architecture by
reading source files directly. It correctly identified both adapter archetypes, noted that
ctags already gives basic Go coverage, described the managed-runtime download pattern by
inspecting `ManagedPythonRuntime`, `ManagedNodeRuntime`, and their common base
`ManagedHttpInstaller`, and proposed the same architectural path (extend
`AbstractModuleLanguageAdapter`). The plan was accurate but required opening 28 source
files versus 22 for the MCP-assisted run, and the agent spent more turns on cross-file
navigation before forming conclusions.

**Metrics:**

| Metric | Value |
|--------|-------|
| Output tokens | 9,616 |
| Cache-read tokens | 900,934 |
| Total billed footprint | 1,029,220 |
| Tool calls | 34 |
| MCP calls | 0 |
| Files opened | 28 |
| Cost — Sonnet 4.6 | $0.8588 |
| Cost — Opus 4.8 | $1.4314 |
| Messages estimated | 3 (551 estimated tokens) |

---

### Task B — with-memgraph

**Findings summary:**

The with-memgraph agent produced a ranked list of 13 performance suspects across four
priority tiers. Using `code_hot_paths` (fan-in/fan-out), `code_operation_hot_paths`, and
`code_resource_risk_scan` it identified:

- **P1-A** `deleteStaleDefinitionsForFile`: 5 sequential CALL{} subquery blocks + 2×UNWIND
  + 3×OPTIONAL MATCH + repeated File node re-match — rated by the scanner as
  many-subquery-blocks, per-row-many-optional-matches, and repeated-root-rematch
  simultaneously.
- **P1-B** `upsert-methods-batch`: 3 independent OPTIONAL MATCH lookups per method row (300
  lookups for 100 methods), running on the highest-fan-in write path (JavaGraphWriter,
  score 41).
- **P1-C** `upsert-file.cypher`: OPTIONAL MATCH on `oldCode` anchor for a migration-guard
  that fires on every file upsert even when irrelevant.
- **P2–P4** suspects covering `upsert-calls-by-name-batch` (unbounded `EXTENDS*`
  traversals), `resolve-pending-calls-scoped`, `upsert-annotated-with-by-fqn` (4×UNION
  label scan), single-item relation-list wrappers (JS/Python only), and an unconditional
  empty batch call in `upsertMemoryChunks`.

Note: `code_resource_risk_scan` returned results based on static Cypher text analysis.
Because the `.cypher` resource files have no source-content in the graph chunk text
(a known MCP gap), the scanner operated on heuristic structural analysis rather than
indexed source; findings were cross-verified against actual Cypher file contents.

**Metrics:**

| Metric | Value |
|--------|-------|
| Output tokens | 10,825 |
| Cache-read tokens | 1,203,888 |
| Total billed footprint | 1,314,929 |
| Tool calls | 32 |
| MCP calls | 9 |
| Files opened | 9 |
| Cost — Sonnet 4.6 | $0.8993 |
| Cost — Opus 4.8 | $1.4989 |
| Messages estimated | 10 (2,516 estimated tokens) |

---

### Task B — without-memgraph

**Findings summary:**

The without-memgraph agent also produced a comprehensive performance audit, though it
arrived by reading Java writer classes and Cypher resources directly. It independently
identified `upsert-calls-by-name-batch` (unbounded `EXTENDS*` traversals, three branches,
no depth cap) as the top suspect, followed by `resolve-pending-calls-scoped` (same
pattern at post-processing time), `deleteStaleDefinitionsForFile`, `upsert-methods-batch`
(3 OPTIONAL MATCH per row), and `upsert-annotated-with-by-fqn` (4×UNION). It correctly
flagged single-item relation-list wrappers (JS/Python) and the `replaceCodeChunksForFile`
unconditional delete-prefix scan. The without-memgraph agent opened 25 files and produced
an overlapping but differently prioritised list compared to the MCP-assisted run:
`upsert-calls-by-name-batch` topped the without-memgraph list whereas the MCP run ranked
`deleteStaleDefinitionsForFile` first based on hot-path scores.

**Metrics:**

| Metric | Value |
|--------|-------|
| Output tokens | 7,432 |
| Cache-read tokens | 577,129 |
| Total billed footprint | 695,164 |
| Tool calls | 28 |
| MCP calls | 0 |
| Files opened | 25 |
| Cost — Sonnet 4.6 | $0.6974 |
| Cost — Opus 4.8 | $1.1623 |
| Messages estimated | 5 (5,886 estimated tokens) |

---

### Task C — with-memgraph

**Findings summary:**

The with-memgraph agent used `code_impact` to map the full blast radius of
`GraphWriter.refreshCodeChunkEmbeddings(EmbeddingSettings, boolean)` without opening any
source files. It reported:

- **Depth-1 caller:** `IngestionOrchestrator.refreshChunkEmbeddings(GraphWriter, boolean)`
  (cross-package: yes; `dirtyOnly` wired from `watchMode`).
- **Depth-2 callers:** `IngestionOrchestrator.runPostProcessing` (passes `false` — full
  refresh) and `WatchSession.ingestChangedFiles` (passes `true` — dirty-only, two call
  sites at lines 164 and 213).
- **Indirect reach:** `IngestionOrchestrator.run(Settings)` → top-level entry; no further
  callers.
- **Test coverage:** `IngestionOrchestratorIT` and `WatchSessionTest` identified as the
  tests to re-run after any signature change.

Result delivered with 6 tool calls, 0 files opened, 4 MCP calls — the most efficient run
in the experiment.

**Metrics:**

| Metric | Value |
|--------|-------|
| Output tokens | 2,764 |
| Cache-read tokens | 94,931 |
| Total billed footprint | 120,145 |
| Tool calls | 6 |
| MCP calls | 4 |
| Files opened | 0 |
| Cost — Sonnet 4.6 | $0.1541 |
| Cost — Opus 4.8 | $0.2569 |
| Messages estimated | 3 (669 estimated tokens) |

---

### Task C — without-memgraph

**Findings summary:**

The without-memgraph agent reconstructed the same blast-radius picture by running grep
across the codebase and reading the relevant files. It correctly mapped both depth-1 and
depth-2 callers, identified `IngesterCli.call()` as an additional depth-3 reach, noted
the package-private visibility of `refreshChunkEmbeddings`, and listed the same five
files/tests to re-inspect. It required 19 tool calls and 4 file opens versus 6 tool calls
and 0 files for the MCP run, but the factual accuracy of both outputs was equivalent.

**Metrics:**

| Metric | Value |
|--------|-------|
| Output tokens | 4,078 |
| Cache-read tokens | 479,409 |
| Total billed footprint | 559,410 |
| Tool calls | 19 |
| MCP calls | 0 |
| Files opened | 4 |
| Cost — Sonnet 4.6 | $0.4895 |
| Cost — Opus 4.8 | $0.8159 |
| Messages estimated | 5 (1,377 estimated tokens) |

---

### Task D — with-memgraph

**Findings summary:**

The with-memgraph agent traced the full data flow from a changed source file to a
vector-search-ready embedding. Key findings:

- The three dirty-marking cases in `upsert-code-chunks-batch.cypher` (hash changed →
  remove embedding + set dirty; hash unchanged + embedding null → set dirty; hash
  unchanged + embedding present → preserve).
- `ChunkEmbeddingRefresher.refresh()` call sequence (six steps: drop obsolete indexes,
  query dimension, clear obsolete embeddings, tag vector-index label, ensure index, then
  count phase).
- `dirtyOnly=watchMode` wiring: full run uses `countStale` (mark-and-count write query);
  watch mode uses `countDirty` (read-only count) — and therefore skips the mark-stale
  step for model/dimension-changed chunks.
- Missing index on `embeddingDirty` in `create-schema.cypher` as a performance
  concern.
- `MemoryChunk` upsert lacks the `case C` reset (never resets `embeddingDirty` to false
  inline; that only happens via `update-metadata` after embedding).

**Metrics:**

| Metric | Value |
|--------|-------|
| Output tokens | 8,864 |
| Cache-read tokens | 811,785 |
| Total billed footprint | 896,224 |
| Tool calls | 32 |
| MCP calls | 7 |
| Files opened | 15 |
| Cost — Sonnet 4.6 | $0.6599 |
| Cost — Opus 4.8 | $1.0998 |
| Messages estimated | 5 (1,608 estimated tokens) |

---

### Task D — without-memgraph

**Findings summary:**

The without-memgraph agent produced an equivalently complete data-flow trace, covering all
five stages (change detection, parse+write, dirty marking, post-processing, embedding
refresh sequence). It additionally documented the `mtime`-skip logic in `StoredFileState`,
the `getFilePathsMissingCodeChunks` backfill guard, the `dimensionCache` session-scoped
HashMap, `dropObsoleteVectorIndexes` behaviour, and the distinction between `countDirty`
(read-only) and `countStale` (write query). It opened 24 files vs. 15 for the MCP run and
produced a slightly longer, more file-survey-oriented output that covered the same
correctness territory.

**Metrics:**

| Metric | Value |
|--------|-------|
| Output tokens | 12,698 |
| Cache-read tokens | 1,099,642 |
| Total billed footprint | 1,229,885 |
| Tool calls | 32 |
| MCP calls | 0 |
| Files opened | 24 |
| Cost — Sonnet 4.6 | $0.9611 |
| Cost — Opus 4.8 | $1.6019 |
| Messages estimated | 5 (1,898 estimated tokens) |

---

### Task E — with-memgraph

**Findings summary:**

The with-memgraph agent pinpointed the stale-embedding bug in
`ChunkEmbeddingRefresher.refresh()`. Root cause: in `dirtyOnly=true` (watch mode) the
early-return `if (dirtyCount == 0 && !required)` and the `useDirty && dirtyCount != null`
branch both skip `countStale`, meaning chunks with `embedding IS NULL` and
`embeddingDirty != true` are never processed. `clearObsoleteChunkEmbeddings` only handles
nodes with non-null `embeddingModel`/`embeddingDimensions`/`embedding`; it does not catch
null-embedding chunks from pre-embedding-era ingestion runs.

Secondary finding: `EmbeddingSettings.MEMORY_CHUNK_METADATA_PROPERTIES` does not exclude
`embeddingDirty`, wasting part of the 256-token embedding window with flag metadata.

Fix proposal: unconditionally run `countStale` as a backfill pass after the dirty-chunk
pass in `dirtyOnly` mode. Files to change: `ChunkEmbeddingRefresher.java`,
`EmbeddingSettings.java`, and a new test in `ChunkEmbeddingRefresherTest.java`.

**Metrics:**

| Metric | Value |
|--------|-------|
| Output tokens | 11,818 |
| Cache-read tokens | 1,058,865 |
| Total billed footprint | 1,147,848 |
| Tool calls | 41 |
| MCP calls | 5 |
| Files opened | 17 |
| Cost — Sonnet 4.6 | $0.7843 |
| Cost — Opus 4.8 | $1.3071 |
| Messages estimated | 10 (7,991 estimated tokens) |

---

### Task E — without-memgraph

**Findings summary:**

The without-memgraph agent also identified the same root cause and applied a fix. It
characterised the two broken sub-cases (dirtyCount==0 early return; dirtyCount>0 without
countStale), explained why the `required=true` path worked correctly (it always calls
`countStale`), and described the dirty vs stale split. It applied the fix directly:
removed the early return and changed `if (useDirty && settings.required())` to
`if (useDirty)`. It added three new tests to `ChunkEmbeddingRefresherTest` and ran them to
BUILD SUCCESS (277 tests, 0 failures).

The without-memgraph run used 103 tool calls and consumed 8,100,626 total billed tokens —
roughly 7× the token footprint of the MCP run — because the agent explored a very wide set
of embedding-related files, ran repeated grep passes, and read the same files multiple
times across turns. The large `output_tokens` count (49,733 — the highest of any run) and
17 estimated messages indicate a long session with many generation turns.

**Metrics:**

| Metric | Value |
|--------|-------|
| Output tokens | 49,733 |
| Cache-read tokens | 7,809,106 |
| Total billed footprint | 8,100,626 |
| Tool calls | 103 |
| MCP calls | 0 |
| Files opened | 24 |
| Cost — Sonnet 4.6 | $3.9954 |
| Cost — Opus 4.8 | $6.6589 |
| Messages estimated | 17 (5,478 estimated tokens) |

---

### Task F — with-memgraph

**Findings summary:**

The with-memgraph agent triaged the failing test
`IngestionOrchestratorIT.reingestionAndWatchRefreshCodeChunksFromJavaDocumentation` by
using `code_test_context` to locate the test and `code_impact` to trace the production
call chain. It identified two concrete regressions between the `bug` branch and `main`
(12.3.4):

- **Bug #1 (critical):** `CodeChunkWrite.params()` sent `Params.SIG` (`"sig"`) instead of
  `Params.SIGNATURE` (`"signature"`), setting `chunk.signature = null` for every upserted
  chunk — a silent data corruption on the Cypher `SET` statement.
- **Bug #2:** `WatchSession.ingestChangedFiles` was missing a `refreshChunkEmbeddings`
  call in the `sourceSnapshot.isEmpty()` early branch, leaving embeddings unrefreshed when
  no source files were discovered.

Additional context: `CommonCodeChunkBuilder` compact text format changes (Words field,
`MIN_WORD_LENGTH=3`, `typeNamesOnly()` for file-chunk Words) introduced between 9c1723e
and b99dfd3 were also documented as the commit history most likely to have touched the
assertion surface.

**Metrics:**

| Metric | Value |
|--------|-------|
| Output tokens | 3,950 |
| Cache-read tokens | 490,982 |
| Total billed footprint | 533,387 |
| Tool calls | 24 |
| MCP calls | 4 |
| Files opened | 2 |
| Cost — Sonnet 4.6 | $0.3507 |
| Cost — Opus 4.8 | $0.5846 |
| Messages estimated | 5 (1,206 estimated tokens) |

---

### Task F — without-memgraph

**Findings summary:**

The without-memgraph agent ran the failing test locally and found it passing on HEAD
(`b99dfd3`) — reporting CANNOT REPRODUCE. It analysed the last five commits, correctly
identified that the 9c1723e RAG chunk text restructure (removed `Language:`, `Path:`,
`Source:` header fields; added `Owner:`, `Words:`) and the b99dfd3 `MIN_WORD_LENGTH` 2→3
change were the most plausible regression surfaces. However, since the test passed locally,
it concluded the failure was likely transient CI infrastructure instability, a
ParseService silent failure returning zero chunks, or an `mtime`-skip issue — all
conditional hypotheses rather than confirmed regressions. It did not consult the `bug`
branch and therefore missed the concrete `CodeChunkWrite.params()` parameter-key bug and
the missing `refreshChunkEmbeddings` call. The without-memgraph run used 42 tool calls,
opened 7 files, and consumed 2,320,008 total billed tokens.

**Metrics:**

| Metric | Value |
|--------|-------|
| Output tokens | 12,891 |
| Cache-read tokens | 2,225,367 |
| Total billed footprint | 2,320,008 |
| Tool calls | 42 |
| MCP calls | 0 |
| Files opened | 7 |
| Cost — Sonnet 4.6 | $1.1675 |
| Cost — Opus 4.8 | $1.9458 |
| Messages estimated | 13 (4,662 estimated tokens) |

---

## 3. Final comparison

### Side-by-side table per task

| Task | Run | Output tokens | Total billed | Tool calls | MCP calls | Files opened | Cost Sonnet 4.6 | Cost Opus 4.8 |
|------|-----|--------------|-------------|-----------|-----------|-------------|----------------|--------------|
| A | with-memgraph | 15,159 | 907,963 | 55 | 28 | 22 | $0.7803 | $1.3005 |
| A | without-memgraph | 9,616 | 1,029,220 | 34 | 0 | 28 | $0.8588 | $1.4314 |
| B | with-memgraph | 10,825 | 1,314,929 | 32 | 9 | 9 | $0.8993 | $1.4989 |
| B | without-memgraph | 7,432 | 695,164 | 28 | 0 | 25 | $0.6974 | $1.1623 |
| C | with-memgraph | 2,764 | 120,145 | 6 | 4 | 0 | $0.1541 | $0.2569 |
| C | without-memgraph | 4,078 | 559,410 | 19 | 0 | 4 | $0.4895 | $0.8159 |
| D | with-memgraph | 8,864 | 896,224 | 32 | 7 | 15 | $0.6599 | $1.0998 |
| D | without-memgraph | 12,698 | 1,229,885 | 32 | 0 | 24 | $0.9611 | $1.6019 |
| E | with-memgraph | 11,818 | 1,147,848 | 41 | 5 | 17 | $0.7843 | $1.3071 |
| E | without-memgraph | 49,733 | 8,100,626 | 103 | 0 | 24 | $3.9954 | $6.6589 |
| F | with-memgraph | 3,950 | 533,387 | 24 | 4 | 2 | $0.3507 | $0.5846 |
| F | without-memgraph | 12,891 | 2,320,008 | 42 | 0 | 7 | $1.1675 | $1.9458 |

### Per-task totals (with + without combined)

| Task | Total billed tokens | Total cost Sonnet 4.6 | Total cost Opus 4.8 |
|------|--------------------|-----------------------|---------------------|
| A | 1,937,183 | $1.6391 | $2.7319 |
| B | 2,010,093 | $1.5967 | $2.6611 |
| C | 679,555 | $0.6436 | $1.0727 |
| D | 2,126,109 | $1.6210 | $2.7017 |
| E | 9,248,474 | $4.7796 | $7.9661 |
| F | 2,853,395 | $1.5182 | $2.5304 |
| **TOTAL** | **18,854,809** | **$11.7984** | **$19.6639** |

### Per-condition totals (with vs without)

| Condition | Total billed tokens | Total cost Sonnet 4.6 | Total cost Opus 4.8 |
|-----------|--------------------|-----------------------|---------------------|
| with-memgraph | 4,920,496 | $3.6286 | $6.0478 |
| without-memgraph | 13,934,313 | $8.1698 | $13.6162 |
| **Ratio (without / with)** | **2.83×** | **2.25×** | **2.25×** |

### Narrative: did Memgraph help?

**Where it helped most — Task C (blast-radius, 78% cost reduction):**  
Task C is the clearest Memgraph win. The `code_impact` tool returned the full two-level
call graph for `refreshCodeChunkEmbeddings` in a single call, requiring no source file
reads at all. The without-memgraph agent needed 19 tool calls and 4 file opens to produce
equivalent output. Cost: $0.1541 vs $0.4895 (68.5% cheaper on Sonnet; 78% cheaper on
total billed tokens).

**Task E (bug diagnosis, 86% token reduction):**  
The without-memgraph agent consumed 8.1M billed tokens vs 1.1M for the MCP-assisted run
— a 7.1× ratio. Both agents identified the same root cause, but the without-memgraph
agent explored a much wider surface (103 tool calls, repeated grep/read passes) before
converging. The MCP agent used `code_flow_context` to narrow scope quickly, then read
only the 17 relevant files. The without-memgraph run also generated 49,733 output tokens
— the highest of any run — indicating many verbose generation turns.

**Task D (data-flow trace, 27% cost reduction):**  
The with-memgraph agent opened 15 files vs 24 and produced a more tightly focused trace.
Both outputs were factually equivalent; the MCP agent converged in fewer source reads.

**Task F (CI triage, 77% cost reduction):**  
Quality diverged here. The with-memgraph agent correctly identified two concrete bugs (the
`Params.SIG` parameter key and the missing `refreshChunkEmbeddings` call) by using
`code_test_context` plus commit comparison. The without-memgraph agent ran the test
locally (it passed on HEAD), could not reproduce the failure, and offered only conditional
hypotheses. The MCP advantage was not just cost — it enabled a qualitatively superior
outcome.

**Task A (architecture exploration, 11% cost reduction):**  
Both runs produced high-quality implementation plans. The MCP agent opened 6 fewer files
and paid 12% less total billed tokens. The difference is moderate because Task A requires
understanding novel implementation patterns that are hard to pre-summarise in graph form;
source reads dominated both runs.

**Task B (performance audit, inverse result):**  
The without-memgraph run cost less ($0.6974 vs $0.8993 Sonnet). The MCP agent accumulated
more cache-read tokens (1.2M vs 577K) due to a longer session with more turns. Both
agents identified the same suspects, but the ordering differed. The `code_resource_risk_scan`
tool flagged the same Cypher files but could not read their source content because `.cypher`
resource files lack indexed chunk text in the graph — the scanner operated on structural
heuristics only. The raw source approach was marginally cheaper here, though the MCP
agent produced a more formally ranked list using quantitative hot-path scores.

**Overall:** across 5 of 6 tasks, the with-memgraph condition was cheaper in total billed
tokens. Aggregated across all 12 runs the with-memgraph condition used 4.9M vs 13.9M
tokens (2.83× fewer), costing $3.63 vs $8.17 on Sonnet 4.6. The savings are concentrated
in Tasks C, E, and F. Task B is an exception where graph context accumulation cost more
than the source-read approach.

### Note on Task B — code_resource_risk_scan and the .cypher file gap

`code_resource_risk_scan` returned risk-flagged rows for several Cypher action files, but
the underlying scan operates on indexed chunk text. The `.cypher` resource files in this
project are indexed as file nodes with definition counts but without their source text in
the chunk body, because the Cypher ingester uses ctags-fallback inventory mode rather than
a first-class Cypher language adapter. This means the scanner's heuristic patterns
(many-subquery-blocks, per-row-optional-match, etc.) fire on structural metadata rather
than actual Cypher source content. The MCP-assisted run compensated by having the agent
read the Cypher files directly for cross-verification, which is the correct protocol.
Until `.cypher` files receive a first-class adapter that populates chunk text with query
source, `code_resource_risk_scan` provides only indicative leads for Cypher performance
work — not authoritative results.
