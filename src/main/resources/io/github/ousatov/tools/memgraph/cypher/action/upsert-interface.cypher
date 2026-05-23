MATCH (p:Package {name: $pkg, project: $project})
MATCH (f:File {path: $path, project: $project})
MERGE (t:Interface {fqn: $fqn, project: $project})
  SET t.name = $name,
      t.packageName = $pkg,
      t.isAbstract = $isAbstract,
      t.visibility = $visibility,
      t.isFinal = $isFinal,
      t.language = $language,
      t.kind = $kind,
      t.modulePath = $modulePath,
      t.framework = $framework,
      t.isExternal = false
MERGE (p)-[:CONTAINS]->(t)
MERGE (f)-[:DEFINES]->(t)
