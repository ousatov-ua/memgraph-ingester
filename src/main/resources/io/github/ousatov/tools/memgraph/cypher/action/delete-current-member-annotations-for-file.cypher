MATCH (member)-[r:ANNOTATED_WITH]->(:Annotation {project: $project})
WHERE member.project = $project
  AND (
    (member:Method AND member.signature IN $methodSignatures)
    OR (member:Field AND member.fqn IN $fieldFqns)
  )
OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(member)
WHERE retainedFile.path <> $path
  AND (size($paths) = 0 OR retainedFile.path IN $paths)
WITH r, count(retainedFile) AS retainedDefinitions
WHERE retainedDefinitions = 0
DELETE r
