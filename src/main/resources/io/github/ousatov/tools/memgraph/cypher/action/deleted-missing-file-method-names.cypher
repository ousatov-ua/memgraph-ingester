MATCH (file:File {project: $project, language: $language})-[:DEFINES]->(method:Method)
WHERE file.path IN $paths
  AND method.project = $project
OPTIONAL MATCH (retained:File {project: $project})-[:DEFINES]->(method)
WHERE retained.retainedSourceToken = $retainedSourceToken
WITH method, count(retained) AS retainedDefinitions
WHERE retainedDefinitions = 0
RETURN DISTINCT method.name AS name
