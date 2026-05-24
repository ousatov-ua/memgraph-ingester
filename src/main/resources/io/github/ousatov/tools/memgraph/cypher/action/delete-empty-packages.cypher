MATCH (p:Package {project: $project})
OPTIONAL MATCH (p)-[:CONTAINS]->(n)
WITH p, count(n) AS contained
WHERE contained = 0
DETACH DELETE p
