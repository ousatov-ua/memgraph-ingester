MATCH (ref:CodeRef {project: $project})
WHERE ref.targetType = 'Method'
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->()
DELETE old
WITH ref
OPTIONAL MATCH (target:Method {project: ref.project, signature: ref.key})
FOREACH (_ IN CASE WHEN target IS NULL THEN [] ELSE [1] END |
  MERGE (ref)-[:RESOLVES_TO]->(target)
)
