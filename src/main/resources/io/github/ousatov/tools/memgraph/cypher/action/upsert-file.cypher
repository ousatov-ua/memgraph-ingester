MERGE (f:File {path: $path, project: $project})
  SET f.lastModified = $lastModified
WITH f
MATCH (code:Code {project: $project})
MERGE (code)-[:CONTAINS]->(f)
