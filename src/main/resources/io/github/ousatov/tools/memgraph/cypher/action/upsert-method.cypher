MATCH (file:File {path: $path, project: $project})
MATCH (owner)
WHERE owner.fqn = $owner AND owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
MERGE (m:Method {signature: $sig, project: $project})
  SET m.name = $name,
      m.returnType = $ret,
      m.isStatic = $isStatic,
      m.visibility = $visibility,
      m.startLine = $start,
      m.endLine = $end,
      m.ownerFqn = $owner,
      m.ownerDisplayName = $ownerDisplayName,
      m.language = $language,
      m.kind = $kind,
      m.isSynthetic = $isSynthetic
MERGE (owner)-[:DECLARES]->(m)
MERGE (file)-[:DEFINES]->(m)
