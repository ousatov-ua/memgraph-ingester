MATCH (chunk:MemoryChunk {project: $project, id: $id})
RETURN chunk.sourceLabel AS sourceLabel,
       chunk.sourceId AS sourceId,
       substring(chunk.text, 0, 240) AS preview
