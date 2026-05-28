MATCH (chunk:CodeChunk {project: $project, path: $path})
DETACH DELETE chunk
