MATCH (:File {path: $path, project: $project})-[:DEFINES]->(owner)
WHERE owner.project = $project
  AND (
    (owner:Class AND NOT owner.fqn IN $classFqns)
    OR (owner:Interface AND NOT owner.fqn IN $interfaceFqns)
    OR (owner:Annotation AND NOT owner.fqn IN $annotationFqns)
  )
DETACH DELETE owner
