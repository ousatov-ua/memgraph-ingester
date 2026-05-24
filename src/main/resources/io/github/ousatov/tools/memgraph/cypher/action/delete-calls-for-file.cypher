MATCH (:File {path: $path, project: $project})-[:DEFINES]->(caller:Method {project: $project})-[r:CALLS]->(:Method {project: $project})
DELETE r
