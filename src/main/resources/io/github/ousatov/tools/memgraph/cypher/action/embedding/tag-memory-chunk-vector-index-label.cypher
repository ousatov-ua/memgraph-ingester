MATCH (chunk:MemoryChunk {project: $project})
SET chunk:__VECTOR_INDEX_LABEL__
RETURN count(chunk) AS count
