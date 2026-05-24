MATCH (file:File {project: $project, language: $language})-[:DEFINES]->(caller:Method {project: $project})-[:PENDING_CALL]->(pending:PendingCall {project: $project})
WHERE NOT file.path IN $paths
DETACH DELETE pending
