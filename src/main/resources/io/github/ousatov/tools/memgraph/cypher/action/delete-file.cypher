MATCH (file:File {path: $path, project: $project})
DETACH DELETE file
