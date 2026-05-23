MATCH (ref:CodeRef {project: $project, targetType: 'Code', key: 'java'})
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->()
DELETE old
WITH ref
MATCH (target:Code {project: ref.project, language: 'java'})
MERGE (ref)-[:RESOLVES_TO]->(target)
