MATCH (sourceFile:File {path: $path, project: $project})-[:DEFINES]->(method:Method)
WHERE method.project = $project
OPTIONAL MATCH (other:File {project: $project})-[:DEFINES]->(method)
WHERE other <> sourceFile
WITH method, count(other) AS retainedDefinitions
WHERE retainedDefinitions = 0
RETURN DISTINCT method.name AS name
