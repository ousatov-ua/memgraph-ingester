UNWIND $rows AS row
WITH row.path AS path,
     row.pkg AS pkg,
     row.fqn AS fqn,
     row.name AS name,
     row.visibility AS visibility,
     row.language AS language,
     row.kind AS kind,
     row.modulePath AS modulePath,
     row.framework AS framework
MATCH (p:Package {name: pkg, project: $project, language: language})
MATCH (f:File {path: path, project: $project})
MERGE (a:Annotation {fqn: fqn, project: $project})
  SET a.name = name,
      a.packageName = pkg,
      a.visibility = visibility,
      a.language = language,
      a.kind = kind,
      a.modulePath = modulePath,
      a.framework = framework,
      a.isExternal = false
MERGE (p)-[:CONTAINS]->(a)
MERGE (f)-[:DEFINES]->(a)
