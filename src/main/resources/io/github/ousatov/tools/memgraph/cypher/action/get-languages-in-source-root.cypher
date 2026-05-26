MATCH (f:File {project: $project})
WHERE f.path = $sourceRoot
  OR f.path STARTS WITH $sourceRootPrefix
WITH DISTINCT f.language AS language
MATCH (:Language {project: $project})-[:CONTAINS]->(code:Code {project: $project, language: language})
MATCH (lang:Language {project: $project})-[:CONTAINS]->(code)
RETURN code.language AS language, lang.name AS languageName
ORDER BY language
