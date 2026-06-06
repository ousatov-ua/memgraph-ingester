MATCH (ref:CodeRef {project: $project, targetType: 'Package'})
WHERE ref.key STARTS WITH 'python:'
WITH ref, substring(ref.key, 7) AS packageName
OPTIONAL MATCH (target:Package {project: ref.project, name: packageName, language: 'python'})
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->(oldTarget)
FOREACH (_ IN CASE WHEN oldTarget IS NOT NULL AND (target IS NULL OR oldTarget <> target) THEN [1] ELSE [] END |
  DELETE old
)
FOREACH (_ IN CASE WHEN target IS NULL THEN [] ELSE [1] END |
  MERGE (ref)-[:RESOLVES_TO]->(target)
)
