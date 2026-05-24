MATCH (file:File {path: $path, project: $project})-[:DEFINES]->(owner)-[r]->()
WHERE owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
  AND type(r) IN ['EXTENDS', 'IMPLEMENTS']
OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(owner)
WHERE retainedFile.path <> file.path
  AND (size($paths) = 0 OR retainedFile.path IN $paths)
WITH r, count(retainedFile) AS retainedDefinitions
WHERE retainedDefinitions = 0
DELETE r
