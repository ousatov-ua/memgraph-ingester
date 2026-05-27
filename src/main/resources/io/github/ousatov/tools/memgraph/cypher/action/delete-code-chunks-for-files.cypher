MATCH (chunk:CodeChunk {project: $project, language: $language})
WHERE (chunk.path = $sourceRoot OR chunk.path STARTS WITH $sourceRootPrefix)
  AND NOT chunk.path IN $paths
DETACH DELETE chunk
