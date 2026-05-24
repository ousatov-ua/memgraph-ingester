MATCH (:File {path: $path, project: $project})-[:DEFINES]->(node {project: $project, language: 'java'})
MATCH (pkg:Package {project: $project, language: 'java'})-[:CONTAINS]->(node)
RETURN pkg.name AS sourceRootHint
ORDER BY sourceRootHint
LIMIT 1
