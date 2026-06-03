MATCH (:Language {project: $project, name: $languageName})-[:CONTAINS]->(code:Code {project: $project, language: $language})
MERGE (f:File {path: $path, project: $project})
  SET f.lastModified = $lastModified,
      f.language = $language
WITH code, f
FOREACH (_ IN CASE WHEN $retainedSourceToken = '' THEN [] ELSE [1] END |
  SET f.retainedSourceToken = $retainedSourceToken
)
WITH code, f
OPTIONAL MATCH (oldCode:Code {project: $project})-[oldRel:CONTAINS]->(f)
WHERE oldCode.language IS NULL OR oldCode.language <> $language
DELETE oldRel
WITH code, f
MERGE (code)-[:CONTAINS]->(f)
