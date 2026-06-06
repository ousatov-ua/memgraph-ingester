MATCH (ref:CodeRef {project: $project, targetType: 'Code'})
WHERE NOT ref.key IN ['java', 'js', 'python']
OPTIONAL MATCH (target:Code {project: ref.project, language: ref.key})
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->(oldTarget)
FOREACH (_ IN CASE WHEN oldTarget IS NOT NULL AND (target IS NULL OR oldTarget <> target) THEN [1] ELSE [] END |
  DELETE old
)
FOREACH (_ IN CASE WHEN target IS NULL THEN [] ELSE [1] END |
  MERGE (ref)-[:RESOLVES_TO]->(target)
)
