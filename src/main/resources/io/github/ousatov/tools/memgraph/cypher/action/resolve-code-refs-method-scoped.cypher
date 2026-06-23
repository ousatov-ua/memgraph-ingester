MATCH (ref:CodeRef {project: $project, targetType: 'Method'})
WHERE ref.key IN $methodSignatures
OPTIONAL MATCH (target:Method {project: ref.project, signature: ref.key})
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->(oldTarget)
FOREACH (_ IN CASE WHEN oldTarget IS NOT NULL AND (target IS NULL OR oldTarget <> target) THEN [1] ELSE [] END |
  DELETE old
)
FOREACH (_ IN CASE WHEN target IS NULL THEN [] ELSE [1] END |
  MERGE (ref)-[:RESOLVES_TO]->(target)
)
