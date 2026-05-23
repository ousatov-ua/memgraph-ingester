MATCH (ref:CodeRef {project: $project})
WHERE ref.targetType = 'Package'
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->()
DELETE old
WITH ref,
     CASE
       WHEN ref.key STARTS WITH 'java:' THEN 'java'
       WHEN ref.key STARTS WITH 'js:' THEN 'javascript'
       ELSE null
     END AS language,
     CASE
       WHEN ref.key STARTS WITH 'java:' THEN substring(ref.key, 5)
       WHEN ref.key STARTS WITH 'js:' THEN substring(ref.key, 3)
       ELSE ref.key
     END AS packageName
OPTIONAL MATCH (candidate:Package {project: ref.project, name: packageName})
WHERE candidate.language = language
  OR language IS NULL
WITH ref, collect(candidate) AS candidates
WITH ref, CASE WHEN size(candidates) = 1 THEN candidates[0] ELSE null END AS target
FOREACH (_ IN CASE WHEN target IS NULL THEN [] ELSE [1] END |
  MERGE (ref)-[:RESOLVES_TO]->(target)
)
