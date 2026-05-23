MATCH (source {project: $project})-[r:EXTENDS]->(target {project: $project})
RETURN count(r) AS value
