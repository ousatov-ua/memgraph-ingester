MATCH (source {project: $project})-[r:ANNOTATED_WITH]->(:Annotation {project: $project})
RETURN count(r) AS value
