MATCH (ref:CodeRef {project: $project, targetType: 'Package'})
WHERE ref.key IN $packageKeys
WITH ref, CASE WHEN ref.key CONTAINS ':' THEN split(ref.key, ':') ELSE [] END AS parts
OPTIONAL MATCH (target:Package {project: ref.project})
WHERE size(parts) = 2
  AND target.language = parts[0]
  AND target.name = parts[1]
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->(oldTarget)
FOREACH (_ IN CASE WHEN oldTarget IS NOT NULL AND (target IS NULL OR oldTarget <> target) THEN [1] ELSE [] END |
  DELETE old
)
FOREACH (_ IN CASE WHEN target IS NULL THEN [] ELSE [1] END |
  MERGE (ref)-[:RESOLVES_TO]->(target)
)
