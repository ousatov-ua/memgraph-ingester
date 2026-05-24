UNWIND $paths AS path
MATCH (f:File {path: path, project: $project, language: $language})
RETURN f.path AS path
