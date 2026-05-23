MATCH (n:Interface {project: $project})
RETURN count(n) AS value
