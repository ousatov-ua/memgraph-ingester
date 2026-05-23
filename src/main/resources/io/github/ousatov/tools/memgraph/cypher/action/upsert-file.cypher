MATCH (code:Code {project: $project})
MERGE (f:File {path: $path, project: $project})
  SET f.lastModified = $lastModified,
      f.language = $language
MERGE (code)-[:CONTAINS]->(f)
