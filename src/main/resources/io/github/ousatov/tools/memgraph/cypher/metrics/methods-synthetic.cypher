MATCH (n:Method {project: $project})
WHERE n.isSynthetic = true
RETURN count(n) AS value
