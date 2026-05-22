MATCH (p:Package {project: $project})
WHERE (p.name = 'js' OR p.name STARTS WITH 'js.')
OPTIONAL MATCH (p)-[:CONTAINS]->(n)
WITH p, count(n) AS contained
WHERE contained = 0
DETACH DELETE p
