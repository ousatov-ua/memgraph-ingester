MATCH (file:File {path: $path, project: $project})-[:DEFINES]->(owner)-[r:ANNOTATED_WITH]->(:Annotation {project: $project})
WHERE owner.project = $project
  AND (
    (owner:Class AND owner.fqn IN $classFqns)
    OR (owner:Interface AND owner.fqn IN $interfaceFqns)
    OR (owner:Annotation AND owner.fqn IN $annotationFqns)
  )
OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(owner)
WHERE retainedFile.path <> file.path
WITH r, count(retainedFile) AS retainedDefinitions
WHERE retainedDefinitions = 0
DELETE r
