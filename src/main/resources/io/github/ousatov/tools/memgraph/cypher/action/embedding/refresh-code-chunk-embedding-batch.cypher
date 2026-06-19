MATCH (chunk:CodeChunk {project: $project, embeddingDirty: true})
WHERE chunk.text IS NOT NULL
  AND NOT (chunk.id IN $excludeIds)
WITH chunk
LIMIT $limit
WITH collect(chunk) AS chunks
WITH chunks, [chunk IN chunks | chunk.id] AS ids
CALL embeddings.node_sentence(chunks, $config)
YIELD success, dimension
RETURN success AS success, dimension AS dimension, ids AS ids
