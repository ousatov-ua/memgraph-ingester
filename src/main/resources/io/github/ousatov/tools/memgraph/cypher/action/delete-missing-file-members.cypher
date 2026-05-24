MATCH (file:File {project: $project, language: $language})-[:DEFINES]->(member)
WHERE NOT file.path IN $paths
  AND member.project = $project
  AND (member:Method OR member:Field)
OPTIONAL MATCH (retained:File {project: $project})-[:DEFINES]->(member)
WHERE retained.path IN $paths
WITH member, count(retained) AS retainedDefinitions
WHERE retainedDefinitions = 0
DETACH DELETE member
