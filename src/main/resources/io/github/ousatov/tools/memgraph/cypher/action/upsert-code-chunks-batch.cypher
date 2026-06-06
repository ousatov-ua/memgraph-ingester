UNWIND $rows AS row
MERGE (chunk:CodeChunk {id: row.id, project: $project})
WITH chunk, row, chunk.textHash AS previousTextHash
OPTIONAL MATCH (source {project: $project})-[rel:HAS_RAG_CHUNK]->(chunk)
DELETE rel
WITH chunk, row, previousTextHash
SET chunk.sourceLabel = row.sourceLabel,
    chunk.sourceId = row.sourceId,
    chunk.language = row.language,
    chunk.path = row.path,
    chunk.ownerFqn = row.ownerFqn,
    chunk.signature = row.signature,
    chunk.name = row.name,
    chunk.kind = row.kind,
    chunk.ragRole = row.ragRole,
    chunk.startLine = row.startLine,
    chunk.endLine = row.endLine,
    chunk.isSynthetic = row.isSynthetic,
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
FOREACH (_ IN CASE
  WHEN previousTextHash = row.textHash THEN [1]
  ELSE []
END |
  SET chunk.embeddingDirty = false
)
