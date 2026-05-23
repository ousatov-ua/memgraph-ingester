MATCH (ref:CodeRef {project: $project, targetType: 'Code', key: 'js'})
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->()
DELETE old
WITH ref
MATCH (target:Code {project: ref.project, language: 'js'})
MERGE (ref)-[:RESOLVES_TO]->(target)
