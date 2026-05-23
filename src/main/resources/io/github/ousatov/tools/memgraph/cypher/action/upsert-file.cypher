MATCH (:Language {project: $project, name: $languageName})-[:CONTAINS]->(code:Code {project: $project, language: $language})
MERGE (f:File {path: $path, project: $project})
  SET f.lastModified = $lastModified,
      f.language = $language
MERGE (code)-[:CONTAINS]->(f)
