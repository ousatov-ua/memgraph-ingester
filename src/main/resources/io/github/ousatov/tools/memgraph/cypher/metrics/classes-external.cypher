MATCH (n:Class {project: $project})
WHERE n.isExternal = true
RETURN count(n) AS value
