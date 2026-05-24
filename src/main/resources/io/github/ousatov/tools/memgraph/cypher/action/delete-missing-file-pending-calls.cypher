MATCH (file:File {project: $project, language: $language})-[:DEFINES]->(owner)-[:DECLARES]->(caller:Method {project: $project})-[:PENDING_CALL]->(pending:PendingCall {project: $project})
WHERE NOT file.path IN $paths
  AND owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
DETACH DELETE pending
