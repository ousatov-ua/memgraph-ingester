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
DETACH DELETE member
