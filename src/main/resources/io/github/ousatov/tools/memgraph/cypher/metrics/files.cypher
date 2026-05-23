MATCH (n:File {project: $project})
RETURN count(n) AS value
