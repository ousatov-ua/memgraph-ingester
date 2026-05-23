MATCH (ref:CodeRef {project: $project, targetType: 'Package'})
WHERE ref.key STARTS WITH 'js:'
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->()
DELETE old
WITH ref, substring(ref.key, 3) AS packageName
MATCH (target:Package {project: ref.project, name: packageName, language: 'js'})
MERGE (ref)-[:RESOLVES_TO]->(target)
