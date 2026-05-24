MATCH (:File {path: $path, project: $project})-[:DEFINES]->(owner)-[:DECLARES]->(method:Method {project: $project})
WHERE owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
  AND NOT method.signature IN $methodSignatures
DETACH DELETE method
