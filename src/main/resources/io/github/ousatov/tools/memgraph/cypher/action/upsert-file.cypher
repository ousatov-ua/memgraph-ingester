MERGE (f:File {path: $path, project: $project})
  SET f.lastModified = $lastModified
WITH f
MATCH (proj:Project {name: $project})
MERGE (proj)-[:CONTAINS]->(f)
