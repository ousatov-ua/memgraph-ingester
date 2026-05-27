MATCH (chunk:CodeChunk {project: $project})
RETURN count(chunk) AS count
