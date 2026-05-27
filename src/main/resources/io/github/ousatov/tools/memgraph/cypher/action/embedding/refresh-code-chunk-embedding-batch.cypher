MATCH (chunk:CodeChunk {project: $project})
WHERE chunk.text IS NOT NULL
  AND (chunk.embedding IS NULL
    OR chunk.embeddingModel IS NULL
    OR chunk.embeddingModel <> $modelName
    OR chunk.embeddingDimensions IS NULL
    OR chunk.embeddingDimensions <> $dimension)
WITH chunk
ORDER BY chunk.id
LIMIT $limit
WITH collect(chunk) AS chunks
WITH chunks, [chunk IN chunks | chunk.id] AS ids
CALL embeddings.node_sentence(chunks, $config)
YIELD success, dimension
RETURN success AS success, dimension AS dimension, ids AS ids
