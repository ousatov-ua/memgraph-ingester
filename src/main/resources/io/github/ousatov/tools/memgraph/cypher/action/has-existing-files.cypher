MATCH (f:File {project: $project, language: $language})
WITH count(f) AS files
RETURN files
