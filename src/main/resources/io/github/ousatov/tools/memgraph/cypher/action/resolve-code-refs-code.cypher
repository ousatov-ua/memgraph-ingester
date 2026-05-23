MATCH (ref:CodeRef {project: $project})
WHERE ref.targetType = 'Code'
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->()
DELETE old
WITH ref,
     CASE
       WHEN ref.key = 'java' THEN 'java'
       WHEN ref.key = 'js' THEN 'javascript'
       ELSE null
     END AS language
OPTIONAL MATCH (candidate:Code {project: ref.project})
WHERE candidate.language = language
  OR (language IS NULL AND ref.key = candidate.project)
WITH ref, collect(candidate) AS candidates
WITH ref,
     CASE
       WHEN ref.key = ref.project THEN candidates
       WHEN size(candidates) = 1 THEN candidates
       ELSE []
     END AS targets
FOREACH (target IN targets |
  MERGE (ref)-[:RESOLVES_TO]->(target)
)
