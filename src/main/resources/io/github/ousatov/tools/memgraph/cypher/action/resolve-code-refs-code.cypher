MATCH (ref:CodeRef {project: $project})
WHERE ref.targetType = 'Code'
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->()
DELETE old
WITH ref,
     CASE
       WHEN ref.key = 'java' THEN 'java'
       WHEN ref.key = 'javascript' THEN 'javascript'
       ELSE null
     END AS language
OPTIONAL MATCH (candidate:Code {project: ref.project})
WHERE candidate.language = language
  OR (language IS NULL AND ref.key = candidate.project)
WITH ref, collect(candidate) AS candidates
WITH ref, CASE WHEN size(candidates) = 1 THEN candidates[0] ELSE null END AS target
FOREACH (_ IN CASE WHEN target IS NULL THEN [] ELSE [1] END |
  MERGE (ref)-[:RESOLVES_TO]->(target)
)
