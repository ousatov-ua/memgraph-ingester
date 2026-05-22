MERGE (f:File {path: $path, project: $project})
  SET f.lastModified = $lastModified,
      f.language = $language
WITH f
MATCH (code:Code {project: $project})
MERGE (code)-[:CONTAINS]->(f)
