UNWIND $rows AS row
WITH row.path AS path,
     row.owner AS ownerFqn,
     row.fqn AS fqn,
     row.name AS name,
     row.type AS type,
     row.isStatic AS isStatic,
     row.visibility AS visibility,
     row.language AS language,
     row.kind AS kind
MATCH (file:File {path: path, project: $project})
MATCH (owner:Annotation {fqn: ownerFqn, project: $project})
MERGE (field:Field {fqn: fqn, project: $project})
  SET field.name = name,
      field.type = type,
      field.isStatic = isStatic,
      field.visibility = visibility,
      field.language = language,
      field.kind = kind
MERGE (owner)-[:DECLARES]->(field)
MERGE (file)-[:DEFINES]->(field)
