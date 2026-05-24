MATCH (:File {path: $path, project: $project})-[:DEFINES]->(member)-[r:ANNOTATED_WITH]->(:Annotation {project: $project})
WHERE member.project = $project
  AND (member:Method OR member:Field)
OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(member)
WHERE retainedFile.path <> $path
  AND (size($paths) = 0 OR retainedFile.path IN $paths)
WITH r, count(retainedFile) AS retainedDefinitions
WHERE retainedDefinitions = 0
DELETE r
