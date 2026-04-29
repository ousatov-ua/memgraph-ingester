MERGE (m:Method {signature: $sig, project: $project})
  SET m.name = $name,
      m.returnType = $ret,
      m.isStatic = $isStatic,
      m.visibility = $visibility,
      m.startLine = $start,
      m.endLine = $end
WITH m
MATCH (owner)
WHERE owner.fqn = $owner AND owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
MERGE (owner)-[:DECLARES]->(m)
