MATCH (:File {path: $path, project: $project})-[:DEFINES]->(owner)-[:DECLARES]->(member)
WHERE owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
  AND member.project = $project
  AND (member:Method OR member:Field)
DETACH DELETE member
