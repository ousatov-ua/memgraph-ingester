MATCH (ref:CodeRef {project: $project, targetType: 'Package'})
WHERE ref.key STARTS WITH 'python:'
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->()
DELETE old
WITH ref, substring(ref.key, 7) AS packageName
MATCH (target:Package {project: ref.project, name: packageName, language: 'python'})
MERGE (ref)-[:RESOLVES_TO]->(target)
