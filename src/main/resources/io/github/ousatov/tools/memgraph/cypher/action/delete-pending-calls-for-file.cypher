MATCH (file:File {path: $path, project: $project})-[:DEFINES]->(owner)
MATCH (owner)-[:DECLARES]->(caller:Method {project: $project})-[:PENDING_CALL]->(pending:PendingCall {project: $project})
DETACH DELETE pending
