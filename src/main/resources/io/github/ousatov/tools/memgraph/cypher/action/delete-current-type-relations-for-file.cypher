MATCH (owner)-[r]->()
WHERE owner.project = $project
  AND (
    (owner:Class AND owner.fqn IN $classFqns)
    OR (owner:Interface AND owner.fqn IN $interfaceFqns)
    OR (owner:Annotation AND owner.fqn IN $annotationFqns)
  )
  AND type(r) IN ['EXTENDS', 'IMPLEMENTS']
DELETE r
