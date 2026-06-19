UNWIND $rows AS row
WITH row.owner AS ownerFqn,
     row.annotFqn AS annotFqn,
     row.annotName AS annotName,
     row.language AS language,
     row.kind AS kind
MERGE (a:Annotation {fqn: annotFqn, project: $project})
  SET a.name = coalesce(a.name, annotName),
      a.language = coalesce(a.language, language),
      a.kind = coalesce(a.kind, kind),
      a.isExternal = coalesce(a.isExternal, true)
WITH ownerFqn, a
MATCH (owner:Interface {fqn: ownerFqn, project: $project})
MERGE (owner)-[:ANNOTATED_WITH]->(a)
