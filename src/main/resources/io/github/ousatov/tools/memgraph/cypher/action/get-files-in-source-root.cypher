MATCH (f:File {project: $project})
WHERE f.path = $sourceRoot
  OR f.path STARTS WITH $sourceRootPrefix
RETURN f.path AS path
