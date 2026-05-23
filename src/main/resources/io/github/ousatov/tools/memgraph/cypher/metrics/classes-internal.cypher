MATCH (n:Class {project: $project})
WHERE n.isExternal = false
RETURN count(n) AS value
