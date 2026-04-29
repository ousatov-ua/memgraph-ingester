MATCH (ref:CodeRef {project: $project})
WHERE ref.targetType = 'File'
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->()
DELETE old
WITH ref
OPTIONAL MATCH (target:File {project: ref.project, path: ref.key})
FOREACH (_ IN CASE WHEN target IS NULL THEN [] ELSE [1] END |
  MERGE (ref)-[:RESOLVES_TO]->(target)
)
