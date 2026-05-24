MATCH (file:File {project: $project, language: $language})-[:DEFINES]->(owner)-[:DECLARES]->(member)
WHERE NOT file.path IN $paths
  AND owner.project = $project
  AND (owner:Class OR owner:Interface OR owner:Annotation)
  AND member.project = $project
  AND (member:Method OR member:Field)
DETACH DELETE member
