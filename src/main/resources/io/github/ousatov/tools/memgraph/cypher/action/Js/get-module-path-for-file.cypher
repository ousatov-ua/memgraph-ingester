MATCH (:File {path: $path, project: $project})-[:DEFINES]->(node {project: $project, language: 'js'})
WHERE node.modulePath IS NOT NULL
  AND node.modulePath <> ''
RETURN node.modulePath AS modulePath
ORDER BY modulePath
LIMIT 1
