MATCH (:File {path: $path, project: $project})-[:DEFINES]->(owner)-[:DECLARES]->(caller:Method {project: $project})-[r:CALLS]->(:Method {project: $project})
WHERE owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
DELETE r
