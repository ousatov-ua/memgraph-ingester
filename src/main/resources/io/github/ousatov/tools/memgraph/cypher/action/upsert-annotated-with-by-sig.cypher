MERGE (a:Annotation {fqn: $annotFqn, project: $project})
WITH a
MATCH (m:Method {signature: $sig, project: $project})
MERGE (m)-[:ANNOTATED_WITH]->(a)
