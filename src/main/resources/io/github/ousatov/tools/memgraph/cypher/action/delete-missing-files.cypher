MATCH (file:File {project: $project, language: $language})
WHERE (file.path = $sourceRoot OR file.path STARTS WITH $sourceRootPrefix)
  AND NOT file.path IN $paths
DETACH DELETE file
