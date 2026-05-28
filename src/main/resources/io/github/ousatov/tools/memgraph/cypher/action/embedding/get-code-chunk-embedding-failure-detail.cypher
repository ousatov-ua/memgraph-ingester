MATCH (chunk:CodeChunk {project: $project, id: $id})
RETURN chunk.path AS path,
       chunk.sourceLabel AS sourceLabel,
       chunk.sourceId AS sourceId,
       substring(chunk.text, 0, 240) AS preview
