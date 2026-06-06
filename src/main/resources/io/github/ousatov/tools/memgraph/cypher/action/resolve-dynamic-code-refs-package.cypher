MATCH (ref:CodeRef {project: $project, targetType: 'Package'})
WHERE ref.key CONTAINS ':'
WITH ref, split(ref.key, ':') AS parts
WHERE NOT parts[0] IN ['java', 'js', 'python']
WITH ref, parts[0] AS language, parts[1] AS packageName
OPTIONAL MATCH (target:Package {project: ref.project, name: packageName, language: language})
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->(oldTarget)
FOREACH (_ IN CASE WHEN oldTarget IS NOT NULL AND (target IS NULL OR oldTarget <> target) THEN [1] ELSE [] END |
  DELETE old
)
FOREACH (_ IN CASE WHEN target IS NULL THEN [] ELSE [1] END |
  MERGE (ref)-[:RESOLVES_TO]->(target)
)
