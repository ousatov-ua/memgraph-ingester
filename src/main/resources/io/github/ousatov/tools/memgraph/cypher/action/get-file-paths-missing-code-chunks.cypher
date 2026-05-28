UNWIND $paths AS path
OPTIONAL MATCH (chunk:CodeChunk {project: $project, path: path})
WITH path, count(chunk) AS chunkCount
WHERE chunkCount = 0
RETURN path AS path
ORDER BY path
