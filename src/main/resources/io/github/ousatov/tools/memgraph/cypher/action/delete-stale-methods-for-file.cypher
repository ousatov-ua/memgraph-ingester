MATCH (sourceFile:File {path: $path, project: $project})-[defines:DEFINES]->(method:Method {project: $project})
WHERE NOT method.signature IN $methodSignatures
DELETE defines
WITH method
OPTIONAL MATCH (other:File {project: $project})-[:DEFINES]->(method)
WITH method, count(other) AS remainingDefinitions
WHERE remainingDefinitions = 0
DETACH DELETE method
