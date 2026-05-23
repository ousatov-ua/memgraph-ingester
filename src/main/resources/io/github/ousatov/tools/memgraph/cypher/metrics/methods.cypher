MATCH (n:Method {project: $project})
WHERE n.isSynthetic = false
RETURN count(n) AS value
