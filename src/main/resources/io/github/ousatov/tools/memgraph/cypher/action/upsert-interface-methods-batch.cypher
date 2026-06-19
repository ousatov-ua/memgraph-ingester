UNWIND $rows AS row
WITH row.path AS path,
     row.owner AS ownerFqn,
     row.sig AS sig,
     row.name AS name,
     row.ret AS ret,
     row.isStatic AS isStatic,
     row.visibility AS visibility,
     row.start AS start,
     row.end AS end,
     row.ownerDisplayName AS ownerDisplayName,
     row.language AS language,
     row.kind AS kind,
     row.isSynthetic AS isSynthetic
MATCH (file:File {path: path, project: $project})
MATCH (owner:Interface {fqn: ownerFqn, project: $project})
MERGE (m:Method {signature: sig, project: $project})
  SET m.name = name,
      m.returnType = ret,
      m.isStatic = isStatic,
      m.visibility = visibility,
      m.startLine = start,
      m.endLine = end,
      m.ownerFqn = ownerFqn,
      m.ownerDisplayName = ownerDisplayName,
      m.language = language,
      m.kind = kind,
      m.isSynthetic = isSynthetic
MERGE (owner)-[:DECLARES]->(m)
MERGE (file)-[:DEFINES]->(m)
