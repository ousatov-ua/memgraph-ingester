MATCH (chunk:MemoryChunk {project: $project})
OPTIONAL MATCH (root:Memory {project: $project})-[memoryRel]->(memory)-[:HAS_RAG_CHUNK]->(chunk)
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
WITH chunk, count(memory) AS currentSources
WHERE currentSources = 0
DETACH DELETE chunk
