MATCH (code:Code {project: $project})
MERGE (p:Package {name: $name, project: $project})
MERGE (code)-[:CONTAINS]->(p)
