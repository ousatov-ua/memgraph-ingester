MATCH (:File {path: $path, project: $project})-[defines:DEFINES]->(owner)
WHERE owner.project = $project
  AND (
    (owner:Class AND NOT owner.fqn IN $classFqns)
    OR (owner:Interface AND NOT owner.fqn IN $interfaceFqns)
    OR (owner:Annotation AND NOT owner.fqn IN $annotationFqns)
  )
DELETE defines
WITH owner
OPTIONAL MATCH (definingFile:File {project: $project})-[:DEFINES]->(owner)
WITH owner, count(definingFile) AS remainingDefinitions
WHERE remainingDefinitions = 0
DETACH DELETE owner
