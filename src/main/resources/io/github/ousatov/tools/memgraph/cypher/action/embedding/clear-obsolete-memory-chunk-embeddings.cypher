MATCH (chunk:MemoryChunk)
WHERE (chunk.embedding IS NOT NULL
    AND (chunk.embeddingModel IS NULL
      OR chunk.embeddingModel <> $modelName
      OR chunk.embeddingDimensions IS NULL
      OR chunk.embeddingDimensions <> $dimension))
  OR (chunk.embeddingModel IS NOT NULL AND chunk.embeddingModel <> $modelName)
  OR (chunk.embeddingDimensions IS NOT NULL AND chunk.embeddingDimensions <> $dimension)
REMOVE chunk.embedding
REMOVE chunk.embeddingModel
REMOVE chunk.embeddingDimensions
SET chunk.embeddingDirty = true,
    chunk.updatedAt = datetime()
RETURN count(chunk) AS count
