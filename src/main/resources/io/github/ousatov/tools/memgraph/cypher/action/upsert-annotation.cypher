MATCH (p:Package {name: $pkg, project: $project})
MATCH (f:File {path: $path, project: $project})
MERGE (a:Annotation {fqn: $fqn, project: $project})
  SET a.name = $name,
      a.packageName = $pkg,
      a.visibility = $visibility,
      a.language = $language,
      a.kind = $kind,
      a.modulePath = $modulePath,
      a.framework = $framework,
      a.isExternal = false
MERGE (p)-[:CONTAINS]->(a)
MERGE (f)-[:DEFINES]->(a)
