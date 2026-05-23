MATCH (n:Method {project: $project})
RETURN count(n) AS value
