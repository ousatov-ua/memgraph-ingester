MATCH (file:File {path: $path, project: $project})-[:DEFINES]->(caller:Method {project: $project})-[:PENDING_CALL]->(pending:PendingCall {project: $project})
OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(caller)
WHERE retainedFile.path <> file.path
  AND (size($paths) = 0 OR retainedFile.path IN $paths)
WITH pending, count(retainedFile) AS retainedDefinitions
WHERE retainedDefinitions = 0
DETACH DELETE pending
