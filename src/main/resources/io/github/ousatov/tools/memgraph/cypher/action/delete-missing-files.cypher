MATCH (file:File {project: $project, language: $language})
WHERE NOT file.path IN $paths
DETACH DELETE file
