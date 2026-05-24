MATCH (sourceFile:File {path: $path, project: $project})-[defines:DEFINES]->(field:Field {project: $project})
WHERE NOT field.fqn IN $fieldFqns
DELETE defines
WITH field
OPTIONAL MATCH (other:File {project: $project})-[:DEFINES]->(field)
WITH field, count(other) AS remainingDefinitions
WHERE remainingDefinitions = 0
DETACH DELETE field
