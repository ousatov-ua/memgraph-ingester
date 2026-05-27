MATCH (chunk:CodeChunk {project: $project, path: $path})
WHERE NOT chunk.id IN $ids
DETACH DELETE chunk
