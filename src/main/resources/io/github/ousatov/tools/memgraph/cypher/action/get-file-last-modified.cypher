MATCH (f:File {path: $path, project: $project})
RETURN f.lastModified AS lastModified
