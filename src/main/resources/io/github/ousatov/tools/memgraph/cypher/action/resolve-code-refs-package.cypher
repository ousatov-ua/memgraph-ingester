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
WITH ref, language, collect(candidate) AS candidates
WITH ref,
     CASE
       WHEN language IS NULL THEN candidates
       WHEN size(candidates) = 1 THEN candidates
       ELSE []
     END AS targets
FOREACH (target IN targets |
  MERGE (ref)-[:RESOLVES_TO]->(target)
)
