MATCH (f:File {project: $project})
WHERE f.path <> $sourceRoot
  AND NOT f.path STARTS WITH $sourceRootPrefix
RETURN f.path AS path
