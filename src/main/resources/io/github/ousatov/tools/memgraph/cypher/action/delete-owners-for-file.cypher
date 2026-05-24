MATCH (:File {path: $path, project: $project})-[:DEFINES]->(owner)
WHERE owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
DETACH DELETE owner
