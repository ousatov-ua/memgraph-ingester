MATCH (:Method {project: $project})-[r:CALLS]->(:Method {project: $project})
RETURN count(r) AS value
