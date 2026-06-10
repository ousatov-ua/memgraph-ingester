# Memgraph vs Regular Tools — Comparison Experiment

## Goal

Find out how much Memgraph MCP helps in real developer work — not just answering
structural questions, but actually doing the things a dev does every day.

---

## Tasks

### Task A — Onboarding / New Feature

> "You are a new engineer joining this project. Your first ticket is to add
> ingestion support for a new source language (for example, Go). Figure out where
> to start: what interfaces or base classes exist, what patterns do the current
> language implementations follow — from source discovery and parsing/analysis
> through graph writing to RAG chunk building, including any managed runtime or
> external tooling bootstrap — and produce a concrete step-by-step plan for adding
> the new language, including every file you would need to create or modify. Do
> not change any code."

Why it's real: the first thing any new dev does. Exercises architecture discovery,
interface/inheritance lookup, and pattern recognition across the whole codebase.

---

### Task B — Production Performance Investigation

> "Ingestion is running slow on large codebases. Find which code paths execute on
> every node write. Flag anything that looks like O(n²) complexity, redundant calls,
> or unnecessary repeated work. Produce a prioritized list of suspects with
> a short justification for each. Do not change any code."

Why it's real: a typical oncall or perf-ticket investigation. Exercises hot-path
tracing, fan-in/fan-out analysis, and call-chain depth under time pressure.

---

### Task C — Safe Refactor / Impact Analysis

> "The signature of
> `io.github.ousatov.tools.memgraph.exe.writer.GraphWriter.refreshCodeChunkEmbeddings(EmbeddingSettings, boolean)`
> needs to change. Before touching anything, map the full blast radius: find the
> target method, all direct callers, their callers one level up, and flag any
> callers that cross module/package boundaries or are called from tests. Produce a
> risk-ranked list of files that must be updated or reviewed. Do not change any
> code."

Why it's real: before any non-trivial refactor a dev needs to know what breaks.
Exercises call-graph depth traversal and cross-boundary impact analysis — the
comparison is Memgraph edge traversal vs. grep-based ripple-hunting.

---

### Task D — Concept-First Semantic Search

> "A user reports: 'Something is wrong with how this tool handles changes. I
> edited source files, moved some logic around, added a new class, and ran a full
> ingestion again. But when I ask questions about my code — especially about the
> new class I added — the assistant either ignores it or gives me the old answer.
> The only workaround I found is deleting the project from the database and
> re-ingesting from scratch. The incremental path is somehow not keeping up.'
>
> You have no class or method names to start from. Starting only from this
> symptom:
>
> 1. Locate every part of the system responsible for deciding whether previously
>    stored knowledge about a source file is still current or needs to be
>    recomputed.
> 2. Find every place where outdated or invalidated knowledge is actively cleared,
>    passively retained, or silently short-circuited.
> 3. Trace the full path from 'source file changed' to 'a question about that
>    file returns a correct, up-to-date answer' — including what happens when the
>    underlying model or its configuration changes between runs.
> 4. Identify the most likely failure points where incremental behavior could
>    silently diverge from a full re-run.
>
> Do not change any code."

Why it's real: real bug triage starts from user-visible behavior, not
implementation vocabulary. This task is specifically designed so the vocabulary
gap is maximally wide: the user speaks of "old answers" and "new class ignored,"
while the implementation uses identifiers like `embeddingDirty`, `textHash`,
`clearObsolete`, `dirtyOnly`, `markStale` — zero lexical overlap with the
symptom. A grep-based agent must guess implementation terms cold and iterate
through many wrong probes. A RAG-enabled agent can search by concept
("stored knowledge freshness," "incremental vs full recompute," "staleness
tracking") and surface the relevant code in one pass. After RAG-guided
discovery, exact call-graph and Cypher resource tracing is still required —
combining RAG-first discovery with precise producer/consumer verification across
Java and Cypher files.

---

### Task E — Implementation / Bug Fix

> "Fix the stale-embedding risk found in Task D for the narrowest confirmed cause.
> Make the smallest production change that preserves existing behavior, add or
> update focused tests, and run the relevant test target. Do not make broad
> refactors or unrelated cleanup."

Why it's real: developer work does not end at investigation. This tests whether
Memgraph helps move from graph/RAG discovery to precise source edits, test
selection, and verification without over-editing.

---

### Task F — Failing Test / CI Triage

> "A CI run reports a failure in
> `IngestionOrchestratorIT.reingestionAndWatchRefreshCodeChunksFromJavaDocumentation`.
> Starting from only that failing test name, find the production behavior under
> test, identify the likely regression surface, patch the bug, and run the focused
> test or the smallest reliable local verification. Include the files changed and
> why."

Why it's real: most maintenance work starts from a failing test or CI check, not
from a clean architecture question. Exercises test-to-production tracing,
call-graph lookups with tests included, targeted edits, and verification.

---

## Execution Runs

Each task is executed twice, independently:

| Run | Tools Available |
|-----|----------------|
| **With Memgraph** | Memgraph MCP + all regular tools (grep, glob, Read, Bash, git log, …) |
| **Without Memgraph** | Regular tools only (grep, glob, Read, Bash, git log, …) — no MCP |

**Isolation rules:**
- No findings, intermediate results, or conclusions may carry over between runs.
- Each run starts from scratch as if the other never happened.
- Runs may happen in the same session; context from one must not inform the other.

---

## Statistics to Collect per Run

### Quantity

| Metric | What to record |
|--------|----------------|
| Total tokens | Output tokens (primary), cache-read, and total footprint — computed from transcripts. See "How to Calculate the Quantitative Statistics" below. |
| Money spent (USD) | Per-run dollar cost computed from the four raw usage counters, priced twice — at Claude Sonnet 4.6 and at Claude Opus 4.8 rates. See "Step 6 — Cost calculation" below. |
| Tool calls | Total number of tool invocations |
| Files opened | Number of distinct source files read |
| Search/refine cycles | How many times a result triggered a follow-up lookup |
| Time-to-first-useful-finding | Subjective: Fast / Medium / Slow |

### Quality

| Metric | What to record |
|--------|-------------|
| Evidence quality | Are claims backed by exact MCP lookups or source lines, not only RAG/grep hits? Rate: Low / Medium / High |
| Key entities found | Interfaces, classes, methods, fields, files, tests, and Cypher/resource files correctly identified |
| Missed entities | Relevant entities later found by the paired run or manual review |
| Architecture/plan completeness (Task A) | All necessary files, extension points, config paths, tests, and risks covered? Rate: Low / Medium / High |
| Hot-path/write-path coverage (Task B) | Java call paths plus DB/Cypher/resource write paths covered? Rate: Low / Medium / High |
| Blast-radius completeness (Task C) | Exact target, direct callers, one-level callers, test callers, and cross-boundary callers covered? Rate: Low / Medium / High |
| Semantic/data-flow completeness (Task D) | Dirty-marking, stale-clearing/retention, batch recompute, vector-index lifecycle, and vector-search-visible embedding metadata flow covered — across Java and Cypher resources? Rate: Low / Medium / High |
| Patch correctness (Task E) | Smallest confirmed fix, no unrelated edits, behavior matches diagnosis? Rate: Low / Medium / High |
| Test/CI diagnosis correctness (Task F) | Failing test mapped to production cause and regression surface correctly? Rate: Low / Medium / High |
| Verification quality | Relevant focused tests or commands run; failures and skipped checks explained |
| False positives | Findings that turned out to be wrong, irrelevant, or unsupported |

---

## How to Calculate the Quantitative Statistics (READ CAREFULLY)

**Rule 0 — Ground truth only.** Do NOT trust what an agent reports about itself. Agents
routinely undercount their own tool calls and sometimes claim they used no MCP tools when
they made dozens of calls. ALL quantitative metrics (tokens, tool calls, files opened, MCP
calls) MUST be computed by parsing each agent's raw transcript. Self-reported numbers may
only be used for the inherently subjective fields (time-to-first-finding, evidence rating,
completeness rating).

**Where the data lives.** Each sub-agent writes one transcript file:
`.../<session>/subagents/workflows/<runId>/agent-<id>.jsonl`
Each line is a JSON object. The ones that matter have `"type": "assistant"` (model turns,
which carry `message.usage` and `message.content[]` tool_use blocks) and `"type": "user"`
(the first user line carries the prompt). A sibling `journal.jsonl` records `started` and
`result` events per agent.

### Step 1 — Map each transcript to its task + run

Read the FIRST `type:"user"` line's text and extract:
- `taskId` from the substring `taskId="A"` (A–F)
- `runType` from `runType="with-memgraph"` or `runType="without-memgraph"`

Do not infer task/run from worktree numbers or file order — read it from the prompt text.

**Pitfall — the statistics/aggregator agent looks like a task run.** If a later agent's
prompt embeds the task agents' findings (e.g. a JSON dump containing `taskId="A"`
`runType="with-memgraph"`), a substring search will misclassify it as a 13th run. Only
accept a transcript whose first user line IS a task prompt (the markers appear in the
prompt header, before any task text), and require the final count to be exactly 12. If you
find 13+ candidates, the extras are aggregator/stats agents — identify and discard them
explicitly, and say so in the methodology section.

### Step 2 — Exclude retries (count one successful run only)

An agent that crashed and was retried leaves an EXTRA `agent-*.jsonl` file. In
`journal.jsonl`, every attempt emits a `started` event but only the successful attempt emits
a `result` event. **Keep only the agent IDs that appear in a `result` event; discard the
rest.** You should end with exactly 12 transcripts (one per task×run). Never sum a failed
attempt's tokens/tools into the totals.

Note: a `result` event alone does not prove a transcript is a task run — non-task agents
(e.g. a statistics agent from the same workflow) also emit `result` events. Use Step 1's
prompt check AND Step 2's result check together; report exactly which agent IDs were
discarded and why (retry vs. non-task agent), not just the count.

### Step 3 — Token calculation

This transcript format does NOT put the full input in one field. Every `type:"assistant"`
line carries `message.usage` with FOUR separate counters:

| Field | Meaning |
|---|---|
| `output_tokens` | tokens the model GENERATED this turn |
| `input_tokens` | only the NON-cached input delta this turn (usually tiny) |
| `cache_creation_input_tokens` | input written into the prompt cache this turn |
| `cache_read_input_tokens` | input re-read FROM cache this turn (usually the bulk) |

**CRITICAL — do NOT sum counters per LINE. Sum them per MESSAGE.** One API response is
split across MULTIPLE JSONL lines (one per content block: text, tool_use, …), and every
line of the same response carries a copy of `message.usage` with the SAME `message.id`.
Two failure modes make naive per-line summation a lottery (verified on real runs):

1. **Double counting** — lines sharing a `message.id` repeat the same usage object, so a
   per-line sum inflates every counter by the blocks-per-message factor, which differs
   between runs and silently skews the comparison.
2. **Placeholder undercounting** — lines often carry the `message_start` placeholder usage
   (`output_tokens` of 1–8), and the final usage delta for a message is sometimes NEVER
   written. In one observed run, a multi-thousand-character final answer was logged as
   8 output tokens while the paired run's equivalent answer logged 3,602 — enough to flip
   the per-task verdict on its own.

**Correct procedure:**

1. Group all `type:"assistant"` lines by `message.id`.
2. For each message, take the **MAX** of each of the four counters across its lines
   (the largest value is the most complete snapshot; placeholders are smaller).
3. **Plausibility-check `output_tokens` against content length.** Compute the message's
   generated-character count: total chars of all `text` blocks plus the JSON length of all
   `tool_use` inputs. Estimate `chars / 3.3` tokens. If the recorded max is below ~25% of
   that estimate and the message has more than ~200 chars, the final usage was never
   written — use the `chars / 3.3` estimate for that message instead.
4. Sum the per-message values across the transcript.
5. Report, per run, how many messages needed estimation and how many tokens the estimates
   contribute — this is a data-quality indicator; a run whose total is dominated by
   estimates should be flagged.

Then report these THREE derived numbers per run (do not collapse them into one — they
answer different questions):

1. **Output tokens** = Σ per-message output (corrected as above).
   → This is the PRIMARY comparison metric. It is pure generation effort and cannot be
     skewed by prompt caching, so it is the fairest with-vs-without number. Lead with it.
2. **Cache-read (context reprocessed)** = Σ per-message `cache_read_input_tokens`.
   → Proxy for iteration depth / how much context was re-fed across turns.
3. **Total billed footprint** = Σ per-message `(input_tokens + output_tokens + cache_creation_input_tokens + cache_read_input_tokens)`.
   → The true "all input + all output" total. It is dominated by cache-read and will look
     huge; always report it ALONGSIDE output tokens, never instead of it.

Do NOT report `input_tokens` alone as "input" — on its own it is misleadingly tiny because
the real input sits in the cache fields. If you only have room for one token number, use
**output tokens** and say so.

Note: any framework-level aggregate (e.g. a workflow "subagent_tokens" headline) is a
weighted/cache-discounted figure and will NOT equal any raw sum above. State that explicitly
rather than trying to reconcile it.

### Step 4 — Tool calls, files opened, MCP usage

Iterate the `message.content[]` array of every assistant line; each block with
`"type": "tool_use"` is one tool call. Deduplicate by the block's `id` field — if the same
tool_use `id` appears on more than one line (same message split across lines), count it once.
- **Tool calls** = count of DISTINCT tool_use block `id`s. (This includes MCP calls — report
  the MCP subset separately, do not double-count it as "extra".)
- **Files opened** = count of DISTINCT `input.file_path` values among tool_use blocks whose
  `name == "Read"`. (Grep/Glob do not count as opening a file.)
- **MCP calls** = count of tool_use blocks whose `name` starts with `mcp__memgraph`. For a
  "without-memgraph" run this MUST be 0; if it is not, the isolation was violated — flag it.

### Step 5 — Per-run reporting

For each of the 12 runs report: output tokens, cache-read, total footprint, tool calls (with
MCP subset), files opened, money spent (per Step 6, at both Sonnet 4.6 and Opus 4.8 rates),
and the estimation indicator from Step 3 (messages estimated / tokens contributed by
estimates). Then provide per-task sums (with + without) and an overall total.
Always state that the numbers are transcript-derived ground truth and which fields (if any)
are self-reported.

**Variance caveat for implementation tasks (E, F).** These runs are dominated by the
edit–compile–test loop (e.g. one run may invoke Maven 16 times where its pair needs 5),
not by lookup-tool choice. A single run per cell is noise-dominated for E/F; either run
them with ≥3 seeds per cell or report them separately from the analysis tasks (A–D) and
say the E/F deltas are within single-run variance.

### Step 6 — Cost calculation (money spent per run)

Convert each run's raw token sums into dollars using the official Claude API pricing page:
<https://platform.claude.com/docs/en/about-claude/pricing>. Compute every run's cost TWICE —
once at **Claude Sonnet 4.6** rates and once at **Claude Opus 4.8** rates — so the
with-vs-without comparison shows what each task would cost on either model.

Rates from that page (USD per million tokens; re-check the page and restate the rates used
in the methodology section if they have changed):

| Usage counter | Sonnet 4.6 | Opus 4.8 |
|---|---|---|
| `input_tokens` (base input) | $3.00 | $5.00 |
| `cache_creation_input_tokens` (5m cache write) | $3.75 | $6.25 |
| `cache_read_input_tokens` (cache read) | $0.30 | $0.50 |
| `output_tokens` (output) | $15.00 | $25.00 |

Formula per run (identical for both models, using that model's rates):

```
cost_usd = ( Σ input_tokens                × base_input_rate
           + Σ cache_creation_input_tokens × cache_write_5m_rate
           + Σ cache_read_input_tokens     × cache_read_rate
           + Σ output_tokens               × output_rate ) / 1,000,000
```

Rules:
- Use the SAME corrected per-message sums from Step 3 (message-id grouping + placeholder
  estimation) — never self-reported numbers and never naive per-line sums, which
  double-count cache traffic. The input-side counters (`input_tokens`, cache write/read)
  are reliable in the `message_start` snapshot, so only `output_tokens` ever needs the
  text-length estimation; cost figures inherit whatever estimation Step 3 applied — flag
  runs whose cost is materially estimate-driven.
- Price ALL four counters; a cost built from output tokens alone is wrong because the
  footprint is dominated by cache traffic.
- Treat all cache writes as 5-minute cache writes (the default). If 1-hour cache writes are
  known to be in play, say so explicitly and use the 1h rate instead ($6.00 Sonnet 4.6 /
  $10.00 Opus 4.8).
- Report cost per run to 4 decimal places, then per-task sums (With Memgraph + Without
  Memgraph) and an overall total — separately for each of the two models.

---

## Output Format

**Write the entire report to `RES.md`** (Markdown, in the repo root). Overwrite it on each
full run. `RES.md` MUST contain, in this order:

1. **Methodology** — a section describing HOW the statistics were gathered, so the numbers
   are reproducible and auditable. State explicitly:
   - That all quantitative metrics are transcript-derived ground truth (not self-reported),
     and which fields (if any) are self-reported/subjective.
   - Where the transcripts live and that retries were excluded (kept only agent IDs with a
     `result` event — exactly 12 runs), naming any discarded agent IDs and whether each was
     a retry or a non-task agent (e.g. the statistics agent itself).
   - The exact token definitions used: **output tokens** (primary), **cache-read**, and
     **total billed footprint** — per the "How to Calculate the Quantitative Statistics"
     section above. State that sums were computed PER MESSAGE (grouped by `message.id`,
     max per counter, text-length estimation for messages with missing final usage), NOT
     per line, and report the per-run estimation indicator (messages estimated / tokens
     contributed). Note that any framework "subagent_tokens" headline is a weighted figure
     that will not reconcile with the raw sums.
   - How tool calls, files opened, and MCP calls were counted, and the result of the
     isolation check (every `without-memgraph` run must show 0 MCP calls).
   - How money spent was computed: the pricing source
     (<https://platform.claude.com/docs/en/about-claude/pricing>), the exact per-MTok rates
     used for Claude Sonnet 4.6 and Claude Opus 4.8, and the formula from "Step 6 — Cost
     calculation" (all four usage counters priced, cache writes assumed 5-minute).
2. **Per-run output**, for each of the 12 runs:
   - **Findings** — the actual answer to the task (plan or suspect list).
   - **Metrics table** — filled with the statistics above.
3. **Final comparison** — a side-by-side table per task and overall, plus a short narrative:
   did Memgraph help, where specifically, and by how much. Add the **sum of tokens used by
   each step** (per-task with + without, and an overall total). Add the **money spent on
   each task** — With Memgraph vs Without Memgraph — priced at both Claude Sonnet 4.6 and
   Claude Opus 4.8 rates per "Step 6 — Cost calculation", plus an overall cost total for
   each model.
