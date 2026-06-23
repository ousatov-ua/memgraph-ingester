MATCH (file:File {project: $project, language: $language})
WHERE file.path IN $paths
DETACH DELETE file
