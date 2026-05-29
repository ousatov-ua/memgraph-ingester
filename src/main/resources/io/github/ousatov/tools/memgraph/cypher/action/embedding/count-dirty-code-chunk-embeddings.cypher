MATCH (chunk:CodeChunk {project: $project, embeddingDirty: true})
WHERE chunk.text IS NOT NULL
RETURN count(chunk) AS count
