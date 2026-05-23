MATCH (n:Package {project: $project})
RETURN count(n) AS value
