MATCH (file:File {path: $path, project: $project})-[:DEFINES]->(caller:Method {project: $project})-[:PENDING_CALL]->(pending:PendingCall {project: $project})
OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(caller)
WHERE retainedFile.path <> file.path
WITH pending, count(retainedFile) AS retainedDefinitions
WHERE retainedDefinitions = 0
DETACH DELETE pending
