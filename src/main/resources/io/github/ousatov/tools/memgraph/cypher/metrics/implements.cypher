MATCH (:Class {project: $project})-[r:IMPLEMENTS]->(:Interface {project: $project})
RETURN count(r) AS value
