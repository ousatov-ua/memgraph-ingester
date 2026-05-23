MATCH (n:Field {project: $project})
RETURN count(n) AS value
