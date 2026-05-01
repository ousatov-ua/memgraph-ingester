MERGE (a:Annotation {fqn: $annotFqn, project: $project})
  SET a.name = coalesce(a.name, $annotName),
      a.isExternal = coalesce(a.isExternal, true)
WITH a
MATCH (owner)
WHERE owner.fqn = $owner AND owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation OR owner:Field)
MERGE (owner)-[:ANNOTATED_WITH]->(a)
