MATCH (sourceFile:File {path: $path, project: $project})-[:DEFINES]->(owner)-[:DECLARES]->(member)
WHERE owner.project = $project
  AND (
    (owner:Class AND NOT owner.fqn IN $classFqns)
    OR (owner:Interface AND NOT owner.fqn IN $interfaceFqns)
    OR (owner:Annotation AND NOT owner.fqn IN $annotationFqns)
  )
  AND member.project = $project
  AND (member:Method OR member:Field)
OPTIONAL MATCH (otherFile:File {project: $project})-[:DEFINES]->(owner)
WHERE otherFile <> sourceFile
WITH member, count(otherFile) AS retainedDefinitions
WHERE retainedDefinitions = 0
DETACH DELETE member
