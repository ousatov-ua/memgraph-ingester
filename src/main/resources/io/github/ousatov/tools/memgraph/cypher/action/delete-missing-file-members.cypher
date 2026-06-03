MATCH (file:File {project: $project, language: $language})-[:DEFINES]->(member)
WHERE (file.path = $sourceRoot OR file.path STARTS WITH $sourceRootPrefix)
  AND NOT file.path IN $paths
  AND member.project = $project
  AND (member:Method OR member:Field)
OPTIONAL MATCH (retained:File {project: $project})-[:DEFINES]->(member)
WHERE retained.retainedSourceToken = $retainedSourceToken
WITH member, count(retained) AS retainedDefinitions
WHERE retainedDefinitions = 0
DETACH DELETE member
