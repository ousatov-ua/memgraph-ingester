MATCH (m:Method {project: $project})
WHERE m.startLine IS NULL
DETACH DELETE m
