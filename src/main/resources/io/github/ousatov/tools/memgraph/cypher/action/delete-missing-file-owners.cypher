MATCH (file:File {project: $project, language: $language})-[:DEFINES]->(owner)
WHERE file.path IN $paths
  AND owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
OPTIONAL MATCH (retained:File {project: $project})-[:DEFINES]->(owner)
WHERE retained.retainedSourceToken = $retainedSourceToken
WITH owner, count(retained) AS retainedDefinitions
WHERE retainedDefinitions = 0
DETACH DELETE owner
