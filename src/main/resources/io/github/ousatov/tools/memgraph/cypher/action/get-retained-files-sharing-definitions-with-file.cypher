MATCH (:File {path: $path, project: $project})-[:DEFINES]->(node)
WHERE node.project = $project
  AND (node:Class OR node:Interface OR node:Annotation OR node:Method OR node:Field)
MATCH (retained:File {project: $project})-[:DEFINES]->(node)
WHERE retained.path <> $path
  AND retained.path IN $paths
RETURN DISTINCT retained.path AS path
ORDER BY path
