MATCH (file:File {path: $path, project: $project})-[:DEFINES]->(owner)
WHERE (owner:Class OR owner:Interface OR owner:Annotation)
  AND (owner.language = 'python' OR owner.language IS NULL)
  AND owner.fqn <> $fqn
  AND NOT (owner.fqn STARTS WITH $modulePrefix)
DETACH DELETE owner
