MERGE (f:Field {fqn: $fqn, project: $project})
  SET f.name = $name,
      f.type = $type,
      f.isStatic = $isStatic,
      f.visibility = $visibility
WITH f
MATCH (owner {fqn: $owner, project: $project})
MERGE (owner)-[:DECLARES]->(f)
