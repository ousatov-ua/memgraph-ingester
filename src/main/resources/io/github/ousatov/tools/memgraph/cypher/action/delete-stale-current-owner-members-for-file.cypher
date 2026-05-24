MATCH (:File {path: $path, project: $project})-[defines:DEFINES]->(member)
MATCH (owner)-[:DECLARES]->(member)
WHERE owner.project = $project
  AND (
    (owner:Class AND owner.fqn IN $classFqns)
    OR (owner:Interface AND owner.fqn IN $interfaceFqns)
    OR (owner:Annotation AND owner.fqn IN $annotationFqns)
  )
  AND member.project = $project
  AND (
    (member:Method AND NOT member.signature IN $methodSignatures)
    OR (member:Field AND NOT member.fqn IN $fieldFqns)
  )
DELETE defines
WITH member
OPTIONAL MATCH (other:File {project: $project})-[:DEFINES]->(member)
WITH member, count(other) AS remainingDefinitions
WHERE remainingDefinitions = 0
DETACH DELETE member
