MATCH (:File {path: $path, project: $project})-[:DEFINES]->(owner)-[:DECLARES]->(member)
WHERE owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
  AND member.project = $project
  AND (member:Method OR member:Field)
OPTIONAL MATCH (other:File {project: $project})-[:DEFINES]->(owner)
WHERE other.path <> $path
WITH member, count(other) AS remainingDefinitions
WHERE remainingDefinitions = 0
DETACH DELETE member
