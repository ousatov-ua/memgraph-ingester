MATCH (chunk:CodeChunk {project: $project})
WHERE NOT chunk:__VECTOR_INDEX_LABEL__
SET chunk:__VECTOR_INDEX_LABEL__
RETURN count(chunk) AS count
