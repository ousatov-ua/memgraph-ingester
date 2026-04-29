MERGE (a:Annotation {fqn: $annotFqn, project: $project})
WITH a
MATCH (owner)
WHERE owner.fqn = $owner AND owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation OR owner:Field)
MERGE (owner)-[:ANNOTATED_WITH]->(a)
