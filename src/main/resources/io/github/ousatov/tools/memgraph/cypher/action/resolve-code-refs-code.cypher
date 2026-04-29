MATCH (ref:CodeRef {project: $project})
WHERE ref.targetType = 'Code'
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->()
DELETE old
WITH ref
OPTIONAL MATCH (target:Code {project: ref.project})
  WHERE ref.key = target.project
FOREACH (_ IN CASE WHEN target IS NULL THEN [] ELSE [1] END |
  MERGE (ref)-[:RESOLVES_TO]->(target)
)
