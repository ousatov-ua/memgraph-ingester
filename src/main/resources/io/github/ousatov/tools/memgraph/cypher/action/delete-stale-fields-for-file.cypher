MATCH (:File {path: $path, project: $project})-[:DEFINES]->(owner)-[:DECLARES]->(field:Field {project: $project})
WHERE owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
  AND NOT field.fqn IN $fieldFqns
DETACH DELETE field
