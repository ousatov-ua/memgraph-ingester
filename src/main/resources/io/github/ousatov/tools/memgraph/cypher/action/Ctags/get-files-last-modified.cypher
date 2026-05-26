UNWIND $paths AS path
MATCH (f:File {path: path, project: $project, language: $language})
MATCH (f)-[:DEFINES]->(:Class {project: $project, language: $language, kind: 'module'})
RETURN f.path AS path, f.lastModified AS lastModified
