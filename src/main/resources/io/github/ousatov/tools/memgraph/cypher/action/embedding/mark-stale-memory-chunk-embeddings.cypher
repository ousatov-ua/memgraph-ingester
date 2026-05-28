MATCH (chunk:MemoryChunk {project: $project})
WHERE chunk.text IS NOT NULL
  AND (chunk.embeddingDirty = true
    OR chunk.embedding IS NULL
    OR chunk.embeddingModel IS NULL
    OR chunk.embeddingModel <> $modelName
    OR chunk.embeddingDimensions IS NULL
    OR chunk.embeddingDimensions <> $dimension)
SET chunk.embeddingDirty = true
RETURN count(chunk) AS count
