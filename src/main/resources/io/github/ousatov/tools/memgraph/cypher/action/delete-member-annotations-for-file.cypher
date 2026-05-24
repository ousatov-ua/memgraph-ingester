MATCH (:File {path: $path, project: $project})-[:DEFINES]->(member)-[r:ANNOTATED_WITH]->(:Annotation {project: $project})
WHERE member.project = $project
  AND (member:Method OR member:Field)
DELETE r
