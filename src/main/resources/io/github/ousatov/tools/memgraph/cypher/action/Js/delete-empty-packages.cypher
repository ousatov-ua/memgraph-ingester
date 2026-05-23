MATCH (p:Package {project: $project})
WHERE (p.language = 'javascript' OR p.language IS NULL)
  AND (p.name = 'js' OR p.name STARTS WITH 'js.')
OPTIONAL MATCH (p)-[:CONTAINS]->(n)
WITH p, count(n) AS contained
WHERE contained = 0
DETACH DELETE p
