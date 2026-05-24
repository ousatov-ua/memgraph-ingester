## Memories

This optional section enables durable Memgraph Memory usage for **`{{PROJECT_NAME}}`**. Every query
MUST include `project: '{{PROJECT_NAME}}'`.

### Memory Triggers

- **Status/pending-work requests:** run Orientation queries first, then check Git if local changes are relevant. Never answer from Git alone unless the user explicitly asks for Git-only status.
- **Orientation reuse:** Orientation queries are session-scoped. If they were already run for `{{PROJECT_NAME}}` in this assistant session, reuse those results for follow-up work and skip rerunning them unless memory was changed, the user asks for a refresh, or the task scope is unrelated.
- **Code changes:** before any code-change task, run Orientation queries for Rules, open Findings, Context, active Tasks, open Questions, and open Risks. Empty results are valid. Skip only if already run in this session.
- **Multi-step work tracking:** for multi-step implementation, debugging, refactoring, documentation, dependency, test, or coverage work, create/update a `Task` as `doing` before edits, even if you expect to finish in the same response.
- **Task close:** set any task you created or updated to `done`, `blocked`, or `cancelled` before final response and verify it. Also save durable findings/decisions when useful.
- **Memory lifecycle changes:** immediately update Task/Risk/Question/Decision/ADR/Idea status in Memgraph before proceeding.
- **Code-related memory:** when creating Task/Decision/Finding/Rule/ADR/Risk/Idea nodes related to code, create at least one `CodeRef` and link `(:Decision|:ADR|:Rule|:Context|:Finding|:Task|:Risk|:Question|:Idea)-[:REFERS_TO]->(:CodeRef)-[:RESOLVES_TO]->(:Code|:Package|:File|:Class|:Interface|:Annotation|:Method|:Field)`.

### Orientation

Run at task start when required:

```cypher
MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_RULE]->(r:Rule)
RETURN r.id, r.severity, r.description ORDER BY r.severity;

MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_FINDING]->(f:Finding)
WHERE f.status = 'open'
RETURN f.id, f.type, f.summary;

MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_CONTEXT]->(c:Context)
RETURN c.id, c.content, c.source;

MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_TASK]->(t:Task)
WHERE t.status IN ['todo', 'doing', 'blocked']
RETURN t.id, t.title, t.status, t.priority
ORDER BY t.priority, t.status;

MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_QUESTION]->(q:Question)
WHERE q.status = 'open'
RETURN q.id, q.title;

MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_RISK]->(r:Risk)
WHERE r.status = 'open'
RETURN r.id, r.title, r.severity;
```

## Memory Schema

**Strict:** no extra properties.

Use `datetime()` for all property `createdAt` and `updatedAt` values; do not use `localDateTime()` for memory timestamps.

| Label       | Key props                      | Additional properties                                                                        |
|-------------|--------------------------------|----------------------------------------------------------------------------------------------|
| `:Memory`   | `project`                      | -                                                                                            |
| `:Decision` | `id`, `project`                | `title`, `topic`, `status`, `rationale`, `consequences`, `createdAt`, `updatedAt`            |
| `:ADR`      | `id`, `project`                | `number`, `title`, `status`, `context`, `decision`, `consequences`, `createdAt`, `updatedAt` |
| `:Rule`     | `id`, `project`                | `title`, `topic`, `severity`, `description`, `createdAt`, `updatedAt`                        |
| `:Context`  | `id`, `project`                | `title`, `topic`, `content`, `source`, `createdAt`, `updatedAt`                              |
| `:Finding`  | `id`, `project`                | `title`, `topic`, `type`, `status`, `summary`, `evidence`, `createdAt`, `updatedAt`          |
| `:Task`     | `id`, `project`                | `title`, `status`, `priority`, `description`, `createdAt`, `updatedAt`                       |
| `:Risk`     | `id`, `project`                | `title`, `topic`, `severity`, `status`, `mitigation`, `createdAt`, `updatedAt`               |
| `:Question` | `id`, `project`                | `title`, `status`, `answer`, `createdAt`, `updatedAt`                                        |
| `:Idea`     | `id`, `project`                | `title`, `topic`, `status`, `notes`, `createdAt`, `updatedAt`                                |
| `:CodeRef`  | `project`, `targetType`, `key` | -                                                                                            |

Controlled values:
- Decision/ADR `status`: `proposed`, `accepted`, `rejected`, `superseded`; ADR also `draft`.
- Rule `severity`: `hard`, `soft`, `recommendation`.
- Finding `type`: `bug`, `perf`, `constraint`, `security`; `status`: `open`, `resolved`, `obsolete`.
- Task `status`: `todo`, `doing`, `done`, `blocked`, `cancelled`; `priority`: `0` critical, `1` high, `2` medium, `3` low, `4` none.
- Risk `severity`: `low`, `medium`, `high`, `critical`; `status`: `open`, `mitigated`, `accepted`, `obsolete`.
- Question `status`: `open`, `answered`, `obsolete`.
- Idea `status`: `proposed`, `accepted`, `rejected`, `obsolete`.

ID prefixes: `DEC-`, `ADR-<n>-`, `RULE-`, `FIND-`, `TASK-`, `RISK-`, `CTX-`, `Q-`, `IDEA-` + `<topic>-<name>`.

Memory links:

```text
(:Project)-[:HAS_MEMORY]->(:Memory)-[:HAS_*]->(:Decision|:ADR|:Rule|:Context|:Finding|:Task|:Risk|:Question|:Idea)
(:Decision|...)-[:REFERS_TO]->(:CodeRef)-[:RESOLVES_TO]->(:Code|:Package|:File|:Class|:Interface|:Annotation|:Method|:Field)
```

`CodeRef.key`: reference language key for `Code` (`java` or `js`), language-prefixed package name for `Package` (`java:<package>` or `js:<package>`), path for `File`, FQN for types/fields, signature for `Method`.

## Memory Policy

Memory is not a changelog. Store only information useful for future decisions, investigations, or implementation work.
Do not create memory nodes just because files changed; routine edits belong in Git diff, tests, and final response.
Use concise Markdown in free-text memory fields when it improves scanning, such as `content`, `summary`, `evidence`, `description`, `rationale`, `consequences`, `mitigation`, `answer`, or `notes`. Keep it short and summarizable.

A `Task` is for durable work tracking, not every assistant action.
Create or update a `Task` for explicit tracking/follow-up requests, unfinished or blocked work, continuation of an existing Task, or multi-step implementation/debugging/refactoring/documentation/dependency/test/coverage work.
Create the `Task` even when the work is completed in the same response.
Do not create a `Task` for one-off read-only requests, status checks, simple lookups, or pure verification that does not modify files.
When unsure: if multi-step file edits are involved, create a `Task`; otherwise prefer not creating one.

Create/update:
- `Decision` for design or implementation choices.
- `ADR` for architecture direction.
- `Rule` for future constraints.
- `Finding` for bugs, performance issues, wrong assumptions, or codebase limitations.
- `Context` for durable subsystem behavior, operational caveats, recurring failure modes, or reusable investigation summaries.
- `Task` for durable tracked work, active implementation/debugging/documentation work, or unfinished follow-up.
- `Question` for open questions.
- `Risk` for new or discovered risks.

For `Context`, prefer updating an existing summary such as `CTX-<topic>-summary`, `CTX-<subsystem>-constraints`, or `CTX-<workflow>-notes`.
Keep content concise and current. Do not use Context for routine file changes, test commands, or information better captured as Decision/ADR/Finding/Task/Risk/Question/Rule.

### Task Lifecycle

```cypher
MATCH (t:Task {id: 'TASK-<id>', project: '{{PROJECT_NAME}}'})
SET t.status = 'doing', t.updatedAt = datetime();

MATCH (t:Task {id: 'TASK-<id>', project: '{{PROJECT_NAME}}'})
SET t.status = 'done', t.updatedAt = datetime();
```

### Saving Memory

#### Interactive input limits

With interactive `mgconsole`, keep memory writes short. Do not paste long one-line statements with
large string literals; store concise summaries and split lifecycle/content/link/verification into
separate statements. If the console waits after a paste, cancel/restart and replay short statements.

Create/update the memory node first:

```cypher
MERGE (m:Memory {project: '{{PROJECT_NAME}}'})
MERGE (d:Decision {id: 'DEC-<topic>-<name>', project: '{{PROJECT_NAME}}'})
SET d.title = '<title>', d.topic = '<topic>', d.status = 'accepted',
    d.rationale = '<rationale>', d.consequences = '<consequences>',
    d.createdAt = coalesce(d.createdAt, datetime()), d.updatedAt = datetime()
MERGE (m)-[:HAS_DECISION]->(d);
```

Link to code in a separate query. Use `MATCH` for existing code nodes; do not `MERGE` them inline because unique constraints can fail.

```cypher
MATCH (d:Decision {id: 'DEC-<topic>-<name>', project: '{{PROJECT_NAME}}'})
MATCH (c:Class {fqn: '<fqn>', project: '{{PROJECT_NAME}}'})
MERGE (ref:CodeRef {project: '{{PROJECT_NAME}}', targetType: 'Class', key: '<fqn>'})
MERGE (d)-[:REFERS_TO]->(ref)
MERGE (ref)-[:RESOLVES_TO]->(c);
```

For `targetType: 'Code'`, use `key: 'java'` or `key: 'js'`. For `targetType: 'Package'`, use `key: 'java:<package>'` or `key: 'js:<package>'`.

Verify recent memory and its code link before the final response. Adapt `HAS_DECISION`,
`:Decision`, and `d` to the memory type just created:

```cypher
MATCH (m:Memory {project: '{{PROJECT_NAME}}'})-[:HAS_DECISION]->(d:Decision)
WHERE d.updatedAt >= datetime() - duration('PT5M')
RETURN d.id AS id, labels(d) AS type;

MATCH (d:Decision {project: '{{PROJECT_NAME}}'})-[:REFERS_TO]->(ref:CodeRef)-[:RESOLVES_TO]->(target)
WHERE d.updatedAt >= datetime() - duration('PT5M')
RETURN d.id AS id, ref.targetType AS targetType, ref.key AS key, labels(target) AS targetLabels;
```

> Memory is not logs. Store only what improves future decisions.
