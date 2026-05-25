MATCH (ref:CodeRef {project: $project, targetType: 'Code', key: 'python'})
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->()
DELETE old
WITH ref
MATCH (target:Code {project: ref.project, language: 'python'})
MERGE (ref)-[:RESOLVES_TO]->(target)
