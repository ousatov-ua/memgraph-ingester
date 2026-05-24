MATCH (:File {path: $path, project: $project})-[:DEFINES]->(owner)-[r]->()
WHERE owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
  AND type(r) IN ['EXTENDS', 'IMPLEMENTS']
DELETE r
