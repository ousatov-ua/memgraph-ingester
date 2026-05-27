MATCH (chunk:MemoryChunk {project: $project})
WHERE chunk.text IS NOT NULL
  AND (chunk.embedding IS NULL
    OR chunk.embeddingModel IS NULL
    OR chunk.embeddingModel <> $modelName
    OR chunk.embeddingDimensions IS NULL
    OR chunk.embeddingDimensions <> $dimension)
RETURN count(chunk) AS count
