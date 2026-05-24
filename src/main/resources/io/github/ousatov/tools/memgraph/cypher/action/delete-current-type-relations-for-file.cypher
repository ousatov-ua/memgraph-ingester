MATCH (owner)-[r]->()
WHERE owner.project = $project
  AND (
    (owner:Class AND owner.fqn IN $classFqns)
    OR (owner:Interface AND owner.fqn IN $interfaceFqns)
    OR (owner:Annotation AND owner.fqn IN $annotationFqns)
  )
  AND type(r) IN ['EXTENDS', 'IMPLEMENTS']
OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(owner)
WHERE retainedFile.path <> $path
  AND (size($paths) = 0 OR retainedFile.path IN $paths)
WITH r, count(retainedFile) AS retainedDefinitions
WHERE retainedDefinitions = 0
DELETE r
