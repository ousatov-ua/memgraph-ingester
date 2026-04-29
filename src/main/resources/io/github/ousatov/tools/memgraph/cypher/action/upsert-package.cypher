MERGE (p:Package {name: $name, project: $project})
WITH p
MATCH (code:Code {project: $project})
MERGE (code)-[:CONTAINS]->(p)
