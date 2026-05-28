UNWIND $rows AS row
MERGE (chunk:MemoryChunk {id: row.id, project: $project})
WITH chunk, row, chunk.textHash AS previousTextHash
OPTIONAL MATCH (source {project: $project})-[rel:HAS_RAG_CHUNK]->(chunk)
DELETE rel
WITH chunk, row, previousTextHash
SET chunk.sourceLabel = row.sourceLabel,
    chunk.sourceId = row.sourceId,
    chunk.text = row.text,
    chunk.textHash = row.textHash,
    chunk.createdAt = coalesce(chunk.createdAt, datetime()),
    chunk.updatedAt = datetime()
FOREACH (_ IN CASE
  WHEN previousTextHash IS NULL OR previousTextHash <> row.textHash THEN [1]
  ELSE []
END |
  REMOVE chunk.embedding
  REMOVE chunk.embeddingModel
  REMOVE chunk.embeddingDimensions
  SET chunk.embeddingDirty = true
)
WITH chunk, row
MATCH (source {project: $project, id: row.sourceId})
WHERE row.sourceLabel IN labels(source)
MERGE (source)-[:HAS_RAG_CHUNK]->(chunk)
