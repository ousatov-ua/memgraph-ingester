MERGE (a:Annotation {fqn: $fqn, project: $project})
  SET a.name = $name,
      a.packageName = $pkg,
      a.visibility = $visibility,
      a.isExternal = false
WITH a
MATCH (p:Package {name: $pkg, project: $project})
MERGE (p)-[:CONTAINS]->(a)
WITH a
MATCH (f:File {path: $path, project: $project})
MERGE (f)-[:DEFINES]->(a)
