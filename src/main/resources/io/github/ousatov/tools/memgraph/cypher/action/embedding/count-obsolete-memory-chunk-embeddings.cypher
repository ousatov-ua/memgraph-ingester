MATCH (chunk:MemoryChunk {project: $project})
WHERE (chunk.embedding IS NOT NULL
    AND (chunk.embeddingModel IS NULL
      OR chunk.embeddingModel <> $modelName
      OR chunk.embeddingDimensions IS NULL
      OR chunk.embeddingDimensions <> $dimension))
  OR (chunk.embeddingModel IS NOT NULL AND chunk.embeddingModel <> $modelName)
  OR (chunk.embeddingDimensions IS NOT NULL AND chunk.embeddingDimensions <> $dimension)
RETURN count(chunk) AS count
