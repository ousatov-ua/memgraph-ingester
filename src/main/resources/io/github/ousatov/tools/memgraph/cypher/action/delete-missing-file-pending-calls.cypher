MATCH (file:File {project: $project, language: $language})-[:DEFINES]->(caller:Method {project: $project})-[:PENDING_CALL]->(pending:PendingCall {project: $project})
WHERE (file.path = $sourceRoot OR file.path STARTS WITH $sourceRootPrefix)
  AND NOT file.path IN $paths
OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(caller)
WHERE retainedFile.retainedSourceToken = $retainedSourceToken
WITH pending, count(retainedFile) AS retainedDefinitions
WHERE retainedDefinitions = 0
DETACH DELETE pending
