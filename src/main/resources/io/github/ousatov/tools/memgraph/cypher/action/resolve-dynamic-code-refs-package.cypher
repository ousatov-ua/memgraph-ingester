MATCH (ref:CodeRef {project: $project, targetType: 'Package'})
WHERE ref.key CONTAINS ':'
WITH ref, split(ref.key, ':') AS parts
WHERE NOT parts[0] IN ['java', 'js', 'python']
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->()
DELETE old
WITH ref, parts[0] AS language, parts[1] AS packageName
MATCH (target:Package {project: ref.project, name: packageName, language: language})
MERGE (ref)-[:RESOLVES_TO]->(target)
