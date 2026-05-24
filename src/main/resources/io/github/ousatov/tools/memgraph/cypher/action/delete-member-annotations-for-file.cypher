MATCH (:File {path: $path, project: $project})-[:DEFINES]->(owner)-[:DECLARES]->(member)-[r:ANNOTATED_WITH]->(:Annotation {project: $project})
WHERE owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
  AND member.project = $project
  AND (member:Method OR member:Field)
DELETE r
