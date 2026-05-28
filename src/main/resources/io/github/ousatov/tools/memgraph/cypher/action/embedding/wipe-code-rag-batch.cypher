MATCH (chunk:CodeChunk {project: $project})
WITH chunk LIMIT $batchSize
DETACH DELETE chunk
RETURN count(chunk) AS deleted
