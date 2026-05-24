MATCH (f:File {project: $project, language: $language})
WHERE f.path = $sourceRoot
  OR f.path STARTS WITH $sourceRootPrefix
RETURN f.path AS path
ORDER BY path
