MERGE (a:Annotation {fqn: $annotFqn, project: $project})
  SET a.name = coalesce(a.name, $annotName),
      a.language = coalesce(a.language, $language),
      a.kind = coalesce(a.kind, $kind),
      a.isExternal = coalesce(a.isExternal, true)
WITH a
MATCH (m:Method {signature: $sig, project: $project})
MERGE (m)-[:ANNOTATED_WITH]->(a)
