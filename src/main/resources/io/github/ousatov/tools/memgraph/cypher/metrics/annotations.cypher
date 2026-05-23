MATCH (n:Annotation {project: $project})
RETURN count(n) AS value
