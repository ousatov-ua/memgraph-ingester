MATCH (chunk:CodeChunk {project: $project, language: $language})
WHERE chunk.path IN $paths
DETACH DELETE chunk
