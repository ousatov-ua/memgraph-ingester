UNWIND $rows AS row
WITH row.path AS path,
     row.pkg AS pkg,
     row.fqn AS fqn,
     row.name AS name,
     row.isAbstract AS isAbstract,
     row.visibility AS visibility,
     row.isFinal AS isFinal,
     row.language AS language,
     row.kind AS kind,
     row.modulePath AS modulePath,
     row.framework AS framework
MATCH (p:Package {name: pkg, project: $project, language: language})
MATCH (f:File {path: path, project: $project})
MERGE (t:Interface {fqn: fqn, project: $project})
  SET t.name = name,
      t.packageName = pkg,
      t.isAbstract = isAbstract,
      t.visibility = visibility,
      t.isFinal = isFinal,
      t.language = language,
      t.kind = kind,
      t.modulePath = modulePath,
      t.framework = framework,
      t.isExternal = false
MERGE (p)-[:CONTAINS]->(t)
MERGE (f)-[:DEFINES]->(t)
