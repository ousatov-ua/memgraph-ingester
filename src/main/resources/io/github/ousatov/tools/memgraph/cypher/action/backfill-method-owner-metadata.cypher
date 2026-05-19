MATCH (owner)-[:DECLARES]->(m:Method {project: $project})
WHERE owner.project = $project
  AND owner.fqn IS NOT NULL
  AND (owner:Class OR owner:Interface OR owner:Annotation)
  AND (m.ownerFqn IS NULL OR m.ownerDisplayName IS NULL)
SET m.ownerFqn = owner.fqn,
    m.ownerDisplayName = coalesce(owner.name, owner.fqn)
