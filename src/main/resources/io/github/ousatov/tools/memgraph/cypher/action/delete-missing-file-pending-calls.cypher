MATCH (file:File {project: $project, language: $language})-[:DEFINES]->(caller:Method {project: $project})-[:PENDING_CALL]->(pending:PendingCall {project: $project})
WHERE NOT file.path IN $paths
OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(caller)
WHERE retainedFile.path IN $paths
WITH pending, count(retainedFile) AS retainedDefinitions
WHERE retainedDefinitions = 0
DETACH DELETE pending
