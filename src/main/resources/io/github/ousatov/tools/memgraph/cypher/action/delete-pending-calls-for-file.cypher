MATCH (file:File {path: $path, project: $project})-[:DEFINES]->(caller:Method {project: $project})-[:PENDING_CALL]->(pending:PendingCall {project: $project})
DETACH DELETE pending
