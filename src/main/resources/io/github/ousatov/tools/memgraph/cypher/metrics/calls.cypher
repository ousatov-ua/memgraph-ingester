MATCH (:Method {project: $project})-[r:CALLS]->(:Method {project: $project})
RETURN coalesce(sum(coalesce(r.count, 1)), 0) AS value
