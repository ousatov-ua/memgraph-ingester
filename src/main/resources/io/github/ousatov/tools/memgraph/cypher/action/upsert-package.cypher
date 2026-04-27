MERGE (p:Package {name: $name, project: $project})
WITH p
MATCH (proj:Project {name: $project})
MERGE (proj)-[:CONTAINS]->(p)
