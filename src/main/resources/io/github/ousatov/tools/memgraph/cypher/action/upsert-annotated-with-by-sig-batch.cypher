UNWIND $rows AS row
WITH row.sig AS sig,
     row.annotFqn AS annotFqn,
     row.annotName AS annotName,
     row.language AS language,
     row.kind AS kind
MERGE (a:Annotation {fqn: annotFqn, project: $project})
  SET a.name = coalesce(a.name, annotName),
      a.language = coalesce(a.language, language),
      a.kind = coalesce(a.kind, kind),
      a.isExternal = coalesce(a.isExternal, true)
WITH sig, a
MATCH (m:Method {signature: sig, project: $project})
WITH m, a
MERGE (m)-[:ANNOTATED_WITH]->(a)
