MERGE (f:Field {fqn: $fqn, project: $project})
  SET f.name = $name,
      f.type = $type,
      f.isStatic = $isStatic,
      f.visibility = $visibility
WITH f
MATCH (owner)
WHERE owner.fqn = $owner AND owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
MERGE (owner)-[:DECLARES]->(f)
