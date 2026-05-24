MATCH (:File {path: $path, project: $project})-[:DEFINES]->(owner)
WHERE owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
OPTIONAL MATCH (other:File {project: $project})-[:DEFINES]->(owner)
WHERE other.path <> $path
WITH owner, count(other) AS remainingDefinitions
WHERE remainingDefinitions = 0
DETACH DELETE owner
