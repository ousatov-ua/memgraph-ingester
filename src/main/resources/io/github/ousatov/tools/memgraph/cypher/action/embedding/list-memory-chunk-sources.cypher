MATCH (root:Memory {project: $project})-[memoryRel]->(memory)
WHERE type(memoryRel) IN [
  'HAS_ADR',
  'HAS_CONTEXT',
  'HAS_DECISION',
  'HAS_FINDING',
  'HAS_IDEA',
  'HAS_QUESTION',
  'HAS_RISK',
  'HAS_RULE',
  'HAS_TASK'
]
WITH memory, labels(memory)[0] AS sourceLabel
OPTIONAL MATCH (memory)-[:HAS_RAG_CHUNK]->(existing:MemoryChunk {project: $project})
WITH memory, sourceLabel, head(collect(existing.id)) AS existingChunkId
OPTIONAL MATCH (memory)-[:REFERS_TO]->(ref:CodeRef {project: $project})
WITH memory, sourceLabel, existingChunkId,
     collect(DISTINCT ref.targetType + ' ' + ref.key) AS codeRefs
RETURN existingChunkId AS existingChunkId,
       sourceLabel AS sourceLabel,
       memory.id AS sourceId,
       memory.title AS title,
       memory.topic AS topic,
       memory.status AS status,
       memory.severity AS severity,
       memory.type AS type,
       memory.priority AS priority,
       memory.source AS source,
       memory.number AS number,
       memory.rationale AS rationale,
       memory.consequences AS consequences,
       memory.content AS content,
       memory.description AS description,
       memory.summary AS summary,
       memory.evidence AS evidence,
       memory.mitigation AS mitigation,
       memory.answer AS answer,
       memory.notes AS notes,
       memory.context AS context,
       memory.decision AS decision,
       codeRefs AS codeRefs
ORDER BY sourceLabel, sourceId
