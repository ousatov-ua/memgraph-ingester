MATCH (sourceFile:File {path: $path, project: $project})-[:DEFINES]->(member)
WHERE member.project = $project
  AND (member:Method OR member:Field)
OPTIONAL MATCH (other:File {project: $project})-[:DEFINES]->(member)
WHERE other <> sourceFile
WITH member, count(other) AS remainingDefinitions
WHERE remainingDefinitions = 0
DETACH DELETE member
