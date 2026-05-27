MATCH (:File {path: $path, project: $project})-[:DEFINES]->(node {project: $project, language: $language})
WHERE node.modulePath IS NOT NULL
  AND node.modulePath <> ''
RETURN node.modulePath AS sourceRootHint
ORDER BY sourceRootHint
LIMIT 1
