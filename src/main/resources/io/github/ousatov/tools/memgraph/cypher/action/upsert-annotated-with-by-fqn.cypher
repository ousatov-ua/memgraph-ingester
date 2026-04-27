MERGE (a:Annotation {fqn: $annotFqn, project: $project})
WITH a
MATCH (owner {fqn: $owner, project: $project})
MERGE (owner)-[:ANNOTATED_WITH]->(a)
