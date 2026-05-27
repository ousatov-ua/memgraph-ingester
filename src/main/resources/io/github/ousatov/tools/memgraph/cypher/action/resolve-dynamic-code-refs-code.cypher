MATCH (ref:CodeRef {project: $project, targetType: 'Code'})
WHERE NOT ref.key IN ['java', 'js', 'python']
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->()
DELETE old
WITH ref
MATCH (target:Code {project: ref.project, language: ref.key})
MERGE (ref)-[:RESOLVES_TO]->(target)
