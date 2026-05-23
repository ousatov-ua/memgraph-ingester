MATCH (:CodeRef {project: $project})-[r:RESOLVES_TO]->()
RETURN count(r) AS value
