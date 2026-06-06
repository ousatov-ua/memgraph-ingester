UNWIND $rows AS row
MERGE (chunk:CodeChunk {id: row.id, project: $project})
WITH chunk, row, chunk.textHash AS previousTextHash
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
  WHEN previousTextHash = row.textHash AND chunk.embedding IS NULL THEN [1]
  ELSE []
END |
  SET chunk.embeddingDirty = true
)
FOREACH (_ IN CASE
  WHEN previousTextHash = row.textHash
    AND chunk.embedding IS NOT NULL
    AND coalesce(chunk.embeddingDirty, false) = false THEN [1]
  ELSE []
END |
  SET chunk.embeddingDirty = false
)
WITH chunk, row
CALL {
  WITH chunk, row
  WITH chunk, row WHERE row.sourceLabel = 'File'
  MATCH (source:File {path: row.sourceId, project: $project})
  MERGE (source)-[:HAS_RAG_CHUNK]->(chunk)
  RETURN 1 AS linked
  UNION
  WITH chunk, row
  WITH chunk, row WHERE row.sourceLabel = 'Class'
  MATCH (source:Class {fqn: row.sourceId, project: $project})
  MERGE (source)-[:HAS_RAG_CHUNK]->(chunk)
  RETURN 1 AS linked
  UNION
  WITH chunk, row
  WITH chunk, row WHERE row.sourceLabel = 'Interface'
  MATCH (source:Interface {fqn: row.sourceId, project: $project})
  MERGE (source)-[:HAS_RAG_CHUNK]->(chunk)
  RETURN 1 AS linked
  UNION
  WITH chunk, row
  WITH chunk, row WHERE row.sourceLabel = 'Annotation'
  MATCH (source:Annotation {fqn: row.sourceId, project: $project})
  MERGE (source)-[:HAS_RAG_CHUNK]->(chunk)
  RETURN 1 AS linked
  UNION
  WITH chunk, row
  WITH chunk, row WHERE row.sourceLabel = 'Method'
  MATCH (source:Method {signature: row.sourceId, project: $project})
  MERGE (source)-[:HAS_RAG_CHUNK]->(chunk)
  RETURN 1 AS linked
  UNION
  WITH chunk, row
  WITH chunk, row WHERE row.sourceLabel = 'Field'
  MATCH (source:Field {fqn: row.sourceId, project: $project})
  MERGE (source)-[:HAS_RAG_CHUNK]->(chunk)
  RETURN 1 AS linked
}
