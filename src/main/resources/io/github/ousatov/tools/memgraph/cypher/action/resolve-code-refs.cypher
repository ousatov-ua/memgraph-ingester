MATCH (ref:CodeRef {project: $project})
CALL {
  WITH ref
  WITH ref WHERE ref.targetType = 'Code'
  OPTIONAL MATCH (target:Code {project: ref.project, language: ref.key})
  RETURN target
  UNION
  WITH ref
  WITH ref WHERE ref.targetType = 'Package' AND ref.key CONTAINS ':'
  WITH ref, split(ref.key, ':') AS parts
  WITH ref, parts[0] AS language, parts[1] AS packageName
  OPTIONAL MATCH (target:Package {project: ref.project, name: packageName, language: language})
  RETURN target
  UNION
  WITH ref
  WITH ref WHERE ref.targetType = 'Package' AND NOT ref.key CONTAINS ':'
  OPTIONAL MATCH (target:Package {project: ref.project, name: '__unresolvable__'})
  RETURN target
  UNION
  WITH ref
  WITH ref WHERE ref.targetType = 'File'
  OPTIONAL MATCH (target:File {project: ref.project, path: ref.key})
  RETURN target
  UNION
  WITH ref
  WITH ref WHERE ref.targetType = 'Class'
  OPTIONAL MATCH (target:Class {project: ref.project, fqn: ref.key})
  RETURN target
  UNION
  WITH ref
  WITH ref WHERE ref.targetType = 'Interface'
  OPTIONAL MATCH (target:Interface {project: ref.project, fqn: ref.key})
  RETURN target
  UNION
  WITH ref
  WITH ref WHERE ref.targetType = 'Annotation'
  OPTIONAL MATCH (target:Annotation {project: ref.project, fqn: ref.key})
  RETURN target
  UNION
  WITH ref
  WITH ref WHERE ref.targetType = 'Method'
  OPTIONAL MATCH (target:Method {project: ref.project, signature: ref.key})
  RETURN target
  UNION
  WITH ref
  WITH ref WHERE ref.targetType = 'Field'
  OPTIONAL MATCH (target:Field {project: ref.project, fqn: ref.key})
  RETURN target
  UNION
  WITH ref
  WITH ref WHERE NOT ref.targetType IN [
    'Code',
    'Package',
    'File',
    'Class',
    'Interface',
    'Annotation',
    'Method',
    'Field'
  ]
  OPTIONAL MATCH (target:Code {project: ref.project, language: '__unresolvable__'})
  RETURN target
}
OPTIONAL MATCH (ref)-[old:RESOLVES_TO]->(oldTarget)
FOREACH (_ IN CASE WHEN oldTarget IS NOT NULL AND (target IS NULL OR oldTarget <> target) THEN [1] ELSE [] END |
  DELETE old
)
FOREACH (_ IN CASE WHEN target IS NULL THEN [] ELSE [1] END |
  MERGE (ref)-[:RESOLVES_TO]->(target)
)
