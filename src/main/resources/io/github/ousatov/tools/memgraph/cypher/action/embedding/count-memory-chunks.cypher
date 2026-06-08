MATCH (chunk:MemoryChunk {project: $project})
RETURN count(chunk) AS count
