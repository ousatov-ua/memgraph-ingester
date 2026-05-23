MATCH (ref:CodeRef {project: $project, targetType: 'Package'})
WHERE ref.key STARTS WITH 'java:'
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->()
DELETE old
WITH ref, substring(ref.key, 5) AS packageName
MATCH (target:Package {project: ref.project, name: packageName, language: 'java'})
MERGE (ref)-[:RESOLVES_TO]->(target)
