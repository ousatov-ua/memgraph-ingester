MATCH (chunk:CodeChunk {project: $project})
WHERE chunk.id IN $ids
SET chunk.embeddingModel = $modelName,
    chunk.embeddingDimensions = $dimension,
    chunk.updatedAt = datetime()
