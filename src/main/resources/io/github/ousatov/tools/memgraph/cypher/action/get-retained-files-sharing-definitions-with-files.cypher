MATCH (missing:File {project: $project})-[:DEFINES]->(node)
WHERE missing.path IN $missingPaths
  AND node.project = $project
  AND (node:Class OR node:Interface OR node:Annotation OR node:Method OR node:Field)
MATCH (retained:File {project: $project})-[:DEFINES]->(node)
WHERE retained.retainedSourceToken = $retainedSourceToken
RETURN DISTINCT retained.path AS path
ORDER BY path
