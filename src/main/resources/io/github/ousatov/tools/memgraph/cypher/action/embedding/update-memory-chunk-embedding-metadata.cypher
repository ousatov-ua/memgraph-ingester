MATCH (chunk:MemoryChunk {project: $project})
WHERE chunk.id IN $ids
SET chunk.embeddingModel = $modelName,
    chunk.embeddingDimensions = $dimension,
    chunk.embeddingDirty = false,
    chunk.updatedAt = datetime()
