MERGE (t:Interface {fqn: $fqn, project: $project})
  SET t.name = $name,
      t.packageName = $pkg,
      t.isAbstract = $isAbstract,
      t.visibility = $visibility
WITH t
MATCH (p:Package {name: $pkg, project: $project})
MERGE (p)-[:CONTAINS]->(t)
WITH t
MATCH (f:File {path: $path, project: $project})
MERGE (f)-[:DEFINES]->(t)
