MATCH (owner)-[:DECLARES]->(caller:Method {project: $project})-[r:CALLS]->(:Method {project: $project})
WHERE owner.project = $project
  AND (
    (owner:Class AND owner.fqn IN $classFqns)
    OR (owner:Interface AND owner.fqn IN $interfaceFqns)
    OR (owner:Annotation AND owner.fqn IN $annotationFqns)
  )
DELETE r
