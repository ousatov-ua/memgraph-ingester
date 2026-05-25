UNWIND $paths AS path
MATCH (f:File {path: path, project: $project, language: 'python'})
MATCH (f)-[:DEFINES]->(:Class {project: $project, language: 'python', kind: 'module'})
RETURN f.path AS path, f.lastModified AS lastModified
