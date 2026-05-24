MATCH (file:File {project: $project, language: $language})-[:DEFINES]->(owner)-[:DECLARES]->(member)
WHERE NOT file.path IN $paths
  AND owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
  AND member.project = $project
  AND (member:Method OR member:Field)
OPTIONAL MATCH (retained:File {project: $project})-[:DEFINES]->(owner)
WHERE retained.path IN $paths
WITH member, count(retained) AS retainedDefinitions
WHERE retainedDefinitions = 0
DETACH DELETE member
