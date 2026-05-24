MATCH (:File {path: $path, project: $project})-[:DEFINES]->(owner)-[r:ANNOTATED_WITH]->(:Annotation {project: $project})
WHERE owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
DELETE r
