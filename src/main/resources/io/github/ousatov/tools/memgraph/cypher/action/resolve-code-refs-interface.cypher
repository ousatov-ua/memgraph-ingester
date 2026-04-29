MATCH (ref:CodeRef {project: $project})
WHERE ref.targetType = 'Interface'
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->()
DELETE old
WITH ref
OPTIONAL MATCH (target:Interface {project: ref.project, fqn: ref.key})
FOREACH (_ IN CASE WHEN target IS NULL THEN [] ELSE [1] END |
  MERGE (ref)-[:RESOLVES_TO]->(target)
)
