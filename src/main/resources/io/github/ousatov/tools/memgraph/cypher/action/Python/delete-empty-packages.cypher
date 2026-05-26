MATCH (p:Package {project: $project})
WHERE (p.language = 'python' OR p.language IS NULL)
  AND (p.name = 'python' OR p.name STARTS WITH 'python.')
OPTIONAL MATCH (p)-[:CONTAINS]->(n)
WITH p, count(n) AS contained
WHERE contained = 0
DETACH DELETE p
