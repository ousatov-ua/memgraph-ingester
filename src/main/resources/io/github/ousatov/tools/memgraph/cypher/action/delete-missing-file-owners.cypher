MATCH (file:File {project: $project, language: $language})-[:DEFINES]->(owner)
WHERE NOT file.path IN $paths
  AND owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
DETACH DELETE owner
