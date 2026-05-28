WITH $rows AS rows
MATCH (chunk:MemoryChunk {project: $project})
WITH chunk, [row IN rows WHERE row.sourceLabel = chunk.sourceLabel AND row.sourceId = chunk.sourceId] AS matches
WHERE size(matches) = 0
DETACH DELETE chunk
